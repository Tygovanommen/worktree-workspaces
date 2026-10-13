package nl.netonic.workspaces.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader/writer so the core has no third-party dependencies.
 * Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link Double}.
 */
public final class Json {
  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  public static Object parse(String text) {
    if (text.startsWith("\uFEFF")) text = text.substring(1); // byte-order mark (Windows Notepad)
    Json p = new Json(text);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) throw p.error("Unexpected trailing content");
    return v;
  }

  private IllegalArgumentException error(String msg) {
    int line = 1;
    for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
    return new IllegalArgumentException(msg + " (line " + line + ")");
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
  }

  private Object value() {
    if (i >= s.length()) throw error("Unexpected end of input");
    char c = s.charAt(i);
    switch (c) {
      case '{': return object();
      case '[': return array();
      case '"': return string();
      case 't': return literal("true", Boolean.TRUE);
      case 'f': return literal("false", Boolean.FALSE);
      case 'n': return literal("null", null);
      default:
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        throw error("Unexpected character '" + c + "'");
    }
  }

  private Object literal(String word, Object result) {
    if (!s.startsWith(word, i)) throw error("Expected " + word);
    i += word.length();
    return result;
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    i++; // {
    ws();
    if (peek() == '}') { i++; return m; }
    while (true) {
      ws();
      if (peek() != '"') throw error("Expected a quoted key");
      String key = string();
      ws();
      if (peek() != ':') throw error("Expected ':'");
      i++;
      ws();
      m.put(key, value());
      ws();
      char c = peek();
      i++;
      if (c == '}') return m;
      if (c != ',') throw error("Expected ',' or '}'");
    }
  }

  private List<Object> array() {
    List<Object> l = new ArrayList<>();
    i++; // [
    ws();
    if (peek() == ']') { i++; return l; }
    while (true) {
      ws();
      l.add(value());
      ws();
      char c = peek();
      i++;
      if (c == ']') return l;
      if (c != ',') throw error("Expected ',' or ']'");
    }
  }

  private char peek() {
    if (i >= s.length()) throw error("Unexpected end of input");
    return s.charAt(i);
  }

  private String string() {
    StringBuilder b = new StringBuilder();
    i++; // opening quote
    while (true) {
      char c = peek();
      i++;
      if (c == '"') return b.toString();
      if (c == '\\') {
        char e = peek();
        i++;
        switch (e) {
          case '"': b.append('"'); break;
          case '\\': b.append('\\'); break;
          case '/': b.append('/'); break;
          case 'b': b.append('\b'); break;
          case 'f': b.append('\f'); break;
          case 'n': b.append('\n'); break;
          case 'r': b.append('\r'); break;
          case 't': b.append('\t'); break;
          case 'u':
            if (i + 4 > s.length()) throw error("Bad unicode escape");
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
            break;
          default: throw error("Bad escape \\" + e);
        }
      } else {
        b.append(c);
      }
    }
  }

  private Double number() {
    int start = i;
    if (peek() == '-') i++;
    while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
    try {
      return Double.valueOf(s.substring(start, i));
    } catch (NumberFormatException e) {
      throw error("Bad number");
    }
  }

  // ---------------------------------------------------------------- writing

  public static String write(Object value) {
    StringBuilder b = new StringBuilder();
    write(b, value, 0);
    b.append('\n');
    return b.toString();
  }

  private static void write(StringBuilder b, Object v, int indent) {
    if (v == null) {
      b.append("null");
    } else if (v instanceof String str) {
      quote(b, str);
    } else if (v instanceof Boolean) {
      b.append(v);
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d)) b.append((long) d); else b.append(d);
    } else if (v instanceof Map<?, ?> m) {
      if (m.isEmpty()) { b.append("{}"); return; }
      b.append("{\n");
      int k = 0;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        pad(b, indent + 1);
        quote(b, String.valueOf(e.getKey()));
        b.append(": ");
        write(b, e.getValue(), indent + 1);
        if (++k < m.size()) b.append(',');
        b.append('\n');
      }
      pad(b, indent);
      b.append('}');
    } else if (v instanceof List<?> l) {
      if (l.isEmpty()) { b.append("[]"); return; }
      boolean simple = l.stream().allMatch(x -> !(x instanceof Map) && !(x instanceof List));
      if (simple) {
        b.append('[');
        for (int k = 0; k < l.size(); k++) {
          if (k > 0) b.append(", ");
          write(b, l.get(k), indent);
        }
        b.append(']');
        return;
      }
      b.append("[\n");
      for (int k = 0; k < l.size(); k++) {
        pad(b, indent + 1);
        write(b, l.get(k), indent + 1);
        if (k < l.size() - 1) b.append(',');
        b.append('\n');
      }
      pad(b, indent);
      b.append(']');
    } else {
      quote(b, v.toString());
    }
  }

  private static void pad(StringBuilder b, int indent) {
    b.append("  ".repeat(indent));
  }

  private static void quote(StringBuilder b, String str) {
    b.append('"');
    for (int k = 0; k < str.length(); k++) {
      char c = str.charAt(k);
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': b.append("\\r"); break;
        case '\t': b.append("\\t"); break;
        default:
          if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
      }
    }
    b.append('"');
  }
}
