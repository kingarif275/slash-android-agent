package com.slash.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GPT-2 byte-level BPE compatible with the tokenizer.json shipped by Chatterbox Nano. */
final class Gpt2BpeTokenizer {
    private static final Pattern PIECES = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+");
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<String, Integer> mergeRanks = new HashMap<>();
    private final Map<String, Integer> specialTokens = new LinkedHashMap<>();
    private final Map<String, int[]> cache = new HashMap<>();
    private final String[] byteEncoder = new String[256];
    private final Pattern specialPattern;

    Gpt2BpeTokenizer(File tokenizerJson) throws Exception {
        JSONObject root = new JSONObject(readUtf8(tokenizerJson));
        JSONObject model = root.getJSONObject("model");
        JSONObject vocab = model.getJSONObject("vocab");
        Iterator<String> vocabularyKeys = vocab.keys();
        while (vocabularyKeys.hasNext()) {
            String token = vocabularyKeys.next();
            vocabulary.put(token, vocab.getInt(token));
        }
        JSONArray merges = model.getJSONArray("merges");
        for (int index = 0; index < merges.length(); index++) {
            Object item = merges.get(index);
            String left;
            String right;
            if (item instanceof JSONArray) {
                left = ((JSONArray) item).getString(0);
                right = ((JSONArray) item).getString(1);
            } else {
                String[] pair = String.valueOf(item).split(" ", 2);
                if (pair.length != 2) continue;
                left = pair[0];
                right = pair[1];
            }
            mergeRanks.put(pairKey(left, right), index);
        }
        JSONArray added = root.optJSONArray("added_tokens");
        if (added != null) {
            for (int index = 0; index < added.length(); index++) {
                JSONObject token = added.getJSONObject(index);
                if (token.optBoolean("special")) specialTokens.put(token.getString("content"), token.getInt("id"));
            }
        }
        buildByteEncoder();
        List<String> names = new ArrayList<>(specialTokens.keySet());
        names.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder expression = new StringBuilder();
        for (String name : names) {
            if (expression.length() > 0) expression.append('|');
            expression.append(Pattern.quote(name));
        }
        specialPattern = expression.length() == 0 ? null : Pattern.compile(expression.toString());
    }

    long[] encode(String text) {
        List<Integer> ids = new ArrayList<>();
        if (specialPattern == null) {
            encodeOrdinary(text, ids);
        } else {
            Matcher specials = specialPattern.matcher(text);
            int cursor = 0;
            while (specials.find()) {
                encodeOrdinary(text.substring(cursor, specials.start()), ids);
                ids.add(specialTokens.get(specials.group()));
                cursor = specials.end();
            }
            encodeOrdinary(text.substring(cursor), ids);
        }
        // The published tokenizer's TemplateProcessing appends two end-of-text markers.
        ids.add(50256);
        ids.add(50256);
        long[] output = new long[ids.size()];
        for (int index = 0; index < ids.size(); index++) output[index] = ids.get(index);
        return output;
    }

    private void encodeOrdinary(String text, List<Integer> output) {
        Matcher matcher = PIECES.matcher(text);
        while (matcher.find()) {
            byte[] bytes = matcher.group().getBytes(StandardCharsets.UTF_8);
            StringBuilder encoded = new StringBuilder(bytes.length);
            for (byte value : bytes) encoded.append(byteEncoder[value & 0xff]);
            int[] ids = bpe(encoded.toString());
            for (int id : ids) output.add(id);
        }
    }

    private int[] bpe(String token) {
        int[] known = cache.get(token);
        if (known != null) return known;
        List<String> symbols = new ArrayList<>();
        for (int offset = 0; offset < token.length();) {
            int value = token.codePointAt(offset);
            symbols.add(new String(Character.toChars(value)));
            offset += Character.charCount(value);
        }
        while (symbols.size() > 1) {
            int bestRank = Integer.MAX_VALUE;
            String bestLeft = null;
            String bestRight = null;
            for (int index = 0; index + 1 < symbols.size(); index++) {
                Integer rank = mergeRanks.get(pairKey(symbols.get(index), symbols.get(index + 1)));
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bestLeft = symbols.get(index);
                    bestRight = symbols.get(index + 1);
                }
            }
            if (bestLeft == null) break;
            List<String> merged = new ArrayList<>();
            for (int index = 0; index < symbols.size();) {
                if (index + 1 < symbols.size()
                        && bestLeft.equals(symbols.get(index))
                        && bestRight.equals(symbols.get(index + 1))) {
                    merged.add(bestLeft + bestRight);
                    index += 2;
                } else {
                    merged.add(symbols.get(index++));
                }
            }
            symbols = merged;
        }
        int[] ids = new int[symbols.size()];
        for (int index = 0; index < symbols.size(); index++) {
            Integer id = vocabulary.get(symbols.get(index));
            if (id == null) throw new IllegalStateException("Tokenizer vocabulary mismatch");
            ids[index] = id;
        }
        cache.put(token, ids);
        return ids;
    }

    private void buildByteEncoder() {
        List<Integer> bytes = new ArrayList<>();
        for (int value = 33; value <= 126; value++) bytes.add(value);
        for (int value = 161; value <= 172; value++) bytes.add(value);
        for (int value = 174; value <= 255; value++) bytes.add(value);
        List<Integer> unicode = new ArrayList<>(bytes);
        int extra = 0;
        for (int value = 0; value < 256; value++) {
            if (!bytes.contains(value)) {
                bytes.add(value);
                unicode.add(256 + extra++);
            }
        }
        for (int index = 0; index < bytes.size(); index++) {
            byteEncoder[bytes.get(index)] = new String(Character.toChars(unicode.get(index)));
        }
    }

    private String pairKey(String left, String right) { return left + '\u0000' + right; }

    private String readUtf8(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
