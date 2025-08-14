package com.watch.limusic.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonArray;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Gson adapter to handle Navidrome/Subsonic `genres` field which may be:
 * - ["Rock", "Pop"]
 * - [{"name": "Rock"}, {"name": "Pop"}]
 * - null or missing
 */
public class GenresAdapter implements JsonDeserializer<List<String>> {
	@Override
	public List<String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		List<String> result = new ArrayList<>();
		if (json == null || json instanceof JsonNull || json.isJsonNull()) {
			return result;
		}

		if (json.isJsonArray()) {
			JsonArray array = json.getAsJsonArray();
			for (JsonElement element : array) {
				String value = extractGenreString(element);
				if (value != null && !value.isEmpty()) {
					result.add(value);
				}
			}
			return result;
		}

		// Occasionally servers might return a single object or primitive
		String single = extractGenreString(json);
		if (single != null && !single.isEmpty()) {
			result.add(single);
		}
		return result;
	}

	private String extractGenreString(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			// Common schemas: {"name":"Rock"} or {"value":"Rock"}
			if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
				return obj.get("name").getAsString();
			}
			if (obj.has("value") && obj.get("value").isJsonPrimitive()) {
				return obj.get("value").getAsString();
			}
			// Some servers might nest: {"genre":"Rock"}
			if (obj.has("genre")) {
				JsonElement g = obj.get("genre");
				if (g.isJsonPrimitive()) return g.getAsString();
				if (g.isJsonObject() || g.isJsonArray()) {
					// attempt to drill down once
					String inner = extractGenreString(g);
					if (inner != null && !inner.isEmpty()) return inner;
				}
			}
		}
		if (element.isJsonArray()) {
			// Flatten nested arrays if any
			JsonArray arr = element.getAsJsonArray();
			for (JsonElement e : arr) {
				String s = extractGenreString(e);
				if (s != null && !s.isEmpty()) return s;
			}
		}
		return null;
	}
} 