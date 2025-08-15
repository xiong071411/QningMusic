package com.watch.limusic.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter for Subsonic/Navidrome discTitles field.
 * Accepts formats:
 * - ["Disc 1", "Disc 2"]
 * - [{"name":"Disc 1"}, {"title":"Disc 2"}] 
 * - [{"1":"Disc 1"}] (fallback: first primitive value)
 * - null / single primitive / single object
 */
public class DiscTitlesAdapter implements JsonDeserializer<List<String>> {
	@Override
	public List<String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
		List<String> result = new ArrayList<>();
		if (json == null || json instanceof JsonNull || json.isJsonNull()) {
			return result;
		}

		if (json.isJsonArray()) {
			JsonArray array = json.getAsJsonArray();
			for (JsonElement element : array) {
				String value = extractTitle(element);
				if (value != null && !value.isEmpty()) {
					result.add(value);
				}
			}
			return result;
		}

		String single = extractTitle(json);
		if (single != null && !single.isEmpty()) {
			result.add(single);
		}
		return result;
	}

	private String extractTitle(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (element.isJsonPrimitive()) return element.getAsString();

		if (element.isJsonObject()) {
			JsonObject obj = element.getAsJsonObject();
			// common keys
			if (obj.has("name") && obj.get("name").isJsonPrimitive()) return obj.get("name").getAsString();
			if (obj.has("title") && obj.get("title").isJsonPrimitive()) return obj.get("title").getAsString();
			if (obj.has("value") && obj.get("value").isJsonPrimitive()) return obj.get("value").getAsString();
			// fallback: first primitive value
			for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
				if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
					return e.getValue().getAsString();
				}
			}
		}

		if (element.isJsonArray()) {
			JsonArray arr = element.getAsJsonArray();
			for (JsonElement e : arr) {
				String s = extractTitle(e);
				if (s != null && !s.isEmpty()) return s;
			}
		}
		return null;
	}
} 