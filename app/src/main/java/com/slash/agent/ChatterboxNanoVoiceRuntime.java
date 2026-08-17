package com.slash.agent;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Four-session, fully local Chatterbox Nano ONNX inference and 24 kHz playback runtime. */
public final class ChatterboxNanoVoiceRuntime {
    public interface Callback { void onComplete(Throwable error); }

    private static final String TAG = "ChatterboxNanoVoice";
    private static final int SAMPLE_RATE = 24_000;
    private static final long START_SPEECH_TOKEN = 6561;
    private static final long STOP_SPEECH_TOKEN = 6562;
    private static final long SILENCE_TOKEN = 4299;
    private static final int SPEECH_VOCABULARY = 6563;

    private final ChatterboxNanoModelManager models;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Random random = new Random(1337);
    private OrtEnvironment environment;
    private OrtSession embedTokens;
    private OrtSession speechEncoder;
    private OrtSession languageModel;
    private OrtSession decoder;
    private OrtSession.Result referenceOutputs;
    private Gpt2BpeTokenizer tokenizer;

    public ChatterboxNanoVoiceRuntime(ChatterboxNanoModelManager models) { this.models = models; }
    public boolean assetsReady() { return models.ready(); }

    public void speak(String text, Callback callback) {
        cancelled.set(true);
        worker.execute(() -> {
            cancelled.set(false);
            Throwable failure = null;
            try {
                if (!models.ready()) throw new IllegalStateException("CHATTERBOX_NANO_ASSETS_MISSING");
                ensureLoaded();
                float[] waveform = synthesize(text == null ? "" : text.trim());
                if (!cancelled.get()) play(waveform);
            } catch (Throwable error) {
                failure = error;
                Log.w(TAG, "CHATTERBOX_NANO_INFERENCE_FAILED", error);
            }
            if (callback != null) callback.onComplete(failure);
        });
    }

    public void cancel() { cancelled.set(true); }

    private synchronized void ensureLoaded() throws Exception {
        if (languageModel != null) return;
        environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 1)));
            embedTokens = environment.createSession(models.artifact("onnx/embed_tokens_fp16.onnx").getAbsolutePath(), options);
            speechEncoder = environment.createSession(models.artifact("onnx/speech_encoder_q4f16.onnx").getAbsolutePath(), options);
            languageModel = environment.createSession(models.artifact("onnx/language_model_q4f16.onnx").getAbsolutePath(), options);
            decoder = environment.createSession(models.artifact("onnx/conditional_decoder_q4.onnx").getAbsolutePath(), options);
            tokenizer = new Gpt2BpeTokenizer(models.artifact("tokenizer.json"));
            encodeReference();
            Log.i(TAG, "VOICE_RUNTIME_READY active=CHATTERBOX_NANO_ONNX");
        } catch (Exception error) {
            closeSessions();
            throw error;
        }
    }

    private void closeSessions() {
        if (referenceOutputs != null) {
            referenceOutputs.close();
            referenceOutputs = null;
        }
        closeQuietly(decoder);
        closeQuietly(languageModel);
        closeQuietly(speechEncoder);
        closeQuietly(embedTokens);
        decoder = null;
        languageModel = null;
        speechEncoder = null;
        embedTokens = null;
        tokenizer = null;
    }

    private void closeQuietly(OrtSession session) {
        if (session == null) return;
        try { session.close(); }
        catch (OrtException error) { Log.w(TAG, "VOICE_SESSION_CLOSE_FAILED", error); }
    }

    private void encodeReference() throws Exception {
        float[] reference = WavAudio.read24kMono(models.speakerReference());
        if (reference.length > SAMPLE_RATE * 15) reference = Arrays.copyOf(reference, SAMPLE_RATE * 15);
        try (OnnxTensor audio = OnnxTensor.createTensor(environment, FloatBuffer.wrap(reference),
                new long[]{1, reference.length})) {
            referenceOutputs = speechEncoder.run(single("audio_values", audio));
        }
        Log.i(TAG, "VOICE_REFERENCE_READY samples=" + reference.length);
    }

    private float[] synthesize(String text) throws Exception {
        if (text.isEmpty()) return new float[0];
        long started = System.nanoTime();
        long[] textIds = tokenizer.encode(text);
        OrtSession.Result conditioning = referenceOutputs;
        if (conditioning == null) throw new IllegalStateException("Chatterbox reference is not encoded");
        OnnxTensor audioFeatures = output(conditioning, "audio_features");
        OnnxTensor promptSpeechTokens = output(conditioning, "audio_tokens");
        OnnxTensor speakerEmbeddings = output(conditioning, "speaker_embeddings");
        OnnxTensor speakerFeatures = output(conditioning, "speaker_features");

        List<Long> generated = new ArrayList<>();
        generated.add(START_SPEECH_TOKEN);
        Map<String, OnnxTensor> cache = createEmptyCache();
        OrtSession.Result cacheOwner = null;
        long attentionLength = 0;
        long position = 0;
        boolean reachedStop = false;

        try {
                long[] currentIds = textIds;
                int maxTokens = Math.max(32, Math.min(192, text.length() * 3 + 24));
                for (int step = 0; step < maxTokens && !cancelled.get(); step++) {
                    OnnxTensor idTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(currentIds), new long[]{1, currentIds.length});
                    OrtSession.Result embeddingResult = embedTokens.run(single("input_ids", idTensor));
                    OnnxTensor modelEmbeds = tensor(embeddingResult.get(0));
                    OnnxTensor inputEmbeds = step == 0 ? concatenateSequence(audioFeatures, modelEmbeds) : copyTensor(modelEmbeds);
                    if (step == 0) attentionLength = inputEmbeds.getInfo().getShape()[1];
                    else attentionLength++;
                    long[] mask = new long[(int) attentionLength];
                    Arrays.fill(mask, 1L);
                    long[] positions;
                    if (step == 0) {
                        positions = new long[(int) attentionLength];
                        for (int index = 0; index < positions.length; index++) positions[index] = index;
                        position = positions.length - 1L;
                    } else {
                        position++;
                        positions = new long[]{position};
                    }
                    OnnxTensor attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), new long[]{1, mask.length});
                    OnnxTensor positionIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(positions), new long[]{1, positions.length});
                    Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
                    inputs.put("inputs_embeds", inputEmbeds);
                    inputs.put("attention_mask", attention);
                    inputs.put("position_ids", positionIds);
                    inputs.putAll(cache);
                    OrtSession.Result next = languageModel.run(inputs);

                    idTensor.close();
                    embeddingResult.close();
                    inputEmbeds.close();
                    attention.close();
                    positionIds.close();
                    if (cacheOwner == null) closeTensors(cache.values());
                    else cacheOwner.close();
                    cacheOwner = next;
                    cache = cacheOutputs(next);

                    long sampled = sample(output(next, "logits"), generated);
                    generated.add(sampled);
                    currentIds = new long[]{sampled};
                    if (sampled == STOP_SPEECH_TOKEN) {
                        reachedStop = true;
                        break;
                    }
                }

                if (cancelled.get()) throw new InterruptedException("Voice generation cancelled");
                long[] prompt = longValues(promptSpeechTokens);
                int generatedCount = generated.size() - 1 - (reachedStop ? 1 : 0);
                long[] speechTokens = new long[prompt.length + generatedCount + 3];
                System.arraycopy(prompt, 0, speechTokens, 0, prompt.length);
                for (int index = 0; index < generatedCount; index++) speechTokens[prompt.length + index] = generated.get(index + 1);
                Arrays.fill(speechTokens, prompt.length + generatedCount, speechTokens.length, SILENCE_TOKEN);
                try (OnnxTensor tokens = OnnxTensor.createTensor(environment, LongBuffer.wrap(speechTokens), new long[]{1, speechTokens.length})) {
                    Map<String, OnnxTensor> decoderInputs = new LinkedHashMap<>();
                    decoderInputs.put("speech_tokens", tokens);
                    decoderInputs.put("speaker_embeddings", speakerEmbeddings);
                    decoderInputs.put("speaker_features", speakerFeatures);
                    try (OrtSession.Result decoded = decoder.run(decoderInputs)) {
                        float[] waveform = floatValues(tensor(decoded.get(0)));
                        Log.i(TAG, "CHATTERBOX_NANO_ONNX tokens=" + generatedCount + " samples=" + waveform.length
                                + " elapsed_ms=" + ((System.nanoTime() - started) / 1_000_000L));
                        return waveform;
                    }
                }
        } finally {
                if (cacheOwner != null) cacheOwner.close();
                else closeTensors(cache.values());
        }
    }

    private Map<String, OnnxTensor> createEmptyCache() throws Exception {
        Map<String, OnnxTensor> cache = new LinkedHashMap<>();
        for (Map.Entry<String, NodeInfo> entry : languageModel.getInputInfo().entrySet()) {
            if (!entry.getKey().startsWith("past_key_values.")) continue;
            TensorInfo info = (TensorInfo) entry.getValue().getInfo();
            long[] declared = info.getShape();
            long heads = declared.length > 1 && declared[1] > 0 ? declared[1] : 12;
            long headDimension = declared.length > 3 && declared[3] > 0 ? declared[3] : 64;
            long[] shape = new long[]{1, heads, 0, headDimension};
            if (info.type == OnnxJavaType.FLOAT16) {
                cache.put(entry.getKey(), OnnxTensor.createTensor(environment, ShortBuffer.allocate(0), shape, OnnxJavaType.FLOAT16));
            } else {
                cache.put(entry.getKey(), OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), shape));
            }
        }
        if (cache.size() != 24) throw new IllegalStateException("Expected 24 Nano KV-cache inputs, found " + cache.size());
        return cache;
    }

    private Map<String, OnnxTensor> cacheOutputs(OrtSession.Result result) {
        Map<String, OnnxTensor> output = new LinkedHashMap<>();
        try {
            for (String name : createCacheNames()) {
                String present = "present." + name.substring("past_key_values.".length());
                output.put(name, output(result, present));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Chatterbox cache output mismatch", error);
        }
        return output;
    }

    private List<String> createCacheNames() throws OrtException {
        List<String> names = new ArrayList<>();
        for (String name : languageModel.getInputInfo().keySet()) if (name.startsWith("past_key_values.")) names.add(name);
        return names;
    }

    private long sample(OnnxTensor logitsTensor, List<Long> generated) {
        float[] all = floatValues(logitsTensor);
        long[] shape = logitsTensor.getInfo().getShape();
        int vocabularySize = Math.min(SPEECH_VOCABULARY, (int) shape[shape.length - 1]);
        int offset = all.length - vocabularySize;
        float[] logits = Arrays.copyOfRange(all, offset, all.length);
        for (long token : generated) {
            int index = (int) token;
            if (index >= 0 && index < logits.length) logits[index] = logits[index] < 0 ? logits[index] * 1.2f : logits[index] / 1.2f;
        }
        Integer[] order = new Integer[logits.length];
        for (int index = 0; index < order.length; index++) order[index] = index;
        Arrays.sort(order, Comparator.comparingDouble((Integer index) -> logits[index]).reversed());
        int limit = Math.min(64, order.length);
        double maximum = logits[order[0]] / 0.8;
        double total = 0;
        double[] weights = new double[limit];
        for (int index = 0; index < limit; index++) {
            weights[index] = Math.exp(logits[order[index]] / 0.8 - maximum);
            total += weights[index];
        }
        double threshold = random.nextDouble() * total;
        double cumulative = 0;
        for (int index = 0; index < limit; index++) {
            cumulative += weights[index];
            if (cumulative >= threshold) return order[index];
        }
        return order[0];
    }

    private OnnxTensor concatenateSequence(OnnxTensor first, OnnxTensor second) throws Exception {
        long[] leftShape = first.getInfo().getShape();
        long[] rightShape = second.getInfo().getShape();
        if (leftShape.length != 3 || rightShape.length != 3 || leftShape[0] != rightShape[0] || leftShape[2] != rightShape[2]) {
            throw new IllegalStateException("Chatterbox embedding shape mismatch");
        }
        long[] shape = new long[]{leftShape[0], leftShape[1] + rightShape[1], leftShape[2]};
        if (first.getInfo().type == OnnxJavaType.FLOAT16) {
            short[] left = shortValues(first);
            short[] right = shortValues(second);
            short[] joined = Arrays.copyOf(left, left.length + right.length);
            System.arraycopy(right, 0, joined, left.length, right.length);
            return OnnxTensor.createTensor(environment, ShortBuffer.wrap(joined), shape, OnnxJavaType.FLOAT16);
        }
        float[] left = floatValues(first);
        float[] right = floatValues(second);
        float[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, joined, left.length, right.length);
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(joined), shape);
    }

    private OnnxTensor copyTensor(OnnxTensor source) throws Exception {
        long[] shape = source.getInfo().getShape();
        if (source.getInfo().type == OnnxJavaType.FLOAT16) {
            return OnnxTensor.createTensor(environment, ShortBuffer.wrap(shortValues(source)), shape, OnnxJavaType.FLOAT16);
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(floatValues(source)), shape);
    }

    private float[] floatValues(OnnxTensor tensor) {
        if (tensor.getInfo().type == OnnxJavaType.FLOAT16) {
            short[] half = shortValues(tensor);
            float[] values = new float[half.length];
            for (int index = 0; index < half.length; index++) values[index] = halfToFloat(half[index]);
            return values;
        }
        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private short[] shortValues(OnnxTensor tensor) {
        ShortBuffer buffer = tensor.getShortBuffer();
        short[] values = new short[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private long[] longValues(OnnxTensor tensor) {
        LongBuffer buffer = tensor.getLongBuffer();
        long[] values = new long[buffer.remaining()];
        buffer.get(values);
        return values;
    }

    private float halfToFloat(short value) {
        int bits = value & 0xffff;
        int sign = (bits & 0x8000) << 16;
        int exponent = (bits >>> 10) & 0x1f;
        int mantissa = bits & 0x3ff;
        int output;
        if (exponent == 0) {
            if (mantissa == 0) output = sign;
            else {
                exponent = 1;
                while ((mantissa & 0x400) == 0) { mantissa <<= 1; exponent--; }
                mantissa &= 0x3ff;
                output = sign | ((exponent + 112) << 23) | (mantissa << 13);
            }
        } else if (exponent == 31) output = sign | 0x7f800000 | (mantissa << 13);
        else output = sign | ((exponent + 112) << 23) | (mantissa << 13);
        return Float.intBitsToFloat(output);
    }

    private void play(float[] waveform) {
        if (waveform.length == 0) return;
        int minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(minimum, 4 * SAMPLE_RATE))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        try {
            track.play();
            int offset = 0;
            while (offset < waveform.length && !cancelled.get()) {
                int written = track.write(waveform, offset, waveform.length - offset, AudioTrack.WRITE_BLOCKING);
                if (written <= 0) break;
                offset += written;
            }
        } finally {
            track.stop();
            track.release();
        }
    }

    private Map<String, OnnxTensor> single(String name, OnnxTensor value) {
        Map<String, OnnxTensor> map = new LinkedHashMap<>();
        map.put(name, value);
        return map;
    }

    private OnnxTensor tensor(OnnxValue value) {
        if (!(value instanceof OnnxTensor)) throw new IllegalStateException("Expected ONNX tensor output");
        return (OnnxTensor) value;
    }

    private OnnxTensor output(OrtSession.Result result, String name) {
        OnnxValue value = result.get(name).orElseThrow(
                () -> new IllegalStateException("Missing ONNX output " + name));
        return tensor(value);
    }

    private void closeTensors(Iterable<OnnxTensor> tensors) {
        for (OnnxTensor tensor : tensors) tensor.close();
    }
}
