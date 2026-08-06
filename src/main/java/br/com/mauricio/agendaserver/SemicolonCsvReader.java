package br.com.mauricio.agendaserver;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Leitor CSV streaming compatível com o formato separado por ponto e vírgula usado nos arquivos públicos do CNPJ.
 * Suporta campos entre aspas, aspas duplicadas e quebras de linha dentro de campo citado.
 */
final class SemicolonCsvReader implements AutoCloseable {
    private final Reader reader;
    private int pushed = -2;

    SemicolonCsvReader(Reader reader) {
        this.reader = reader;
    }

    List<String> next() throws IOException {
        List<String> fields = new ArrayList<>(32);
        StringBuilder field = new StringBuilder(128);
        boolean quoted = false;
        boolean started = false;
        while (true) {
            int value = read();
            if (value < 0) {
                if (!started && field.isEmpty() && fields.isEmpty()) return null;
                fields.add(field.toString());
                return fields;
            }
            started = true;
            char ch = (char) value;
            if (quoted) {
                if (ch == '"') {
                    int next = read();
                    if (next == '"') field.append('"');
                    else {
                        quoted = false;
                        unread(next);
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == ';') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                fields.add(trimCarriageReturn(field));
                return fields;
            } else {
                field.append(ch);
            }
        }
    }

    private static String trimCarriageReturn(StringBuilder value) {
        int length = value.length();
        if (length > 0 && value.charAt(length - 1) == '\r') value.setLength(length - 1);
        return value.toString();
    }

    private int read() throws IOException {
        if (pushed != -2) {
            int value = pushed;
            pushed = -2;
            return value;
        }
        return reader.read();
    }

    private void unread(int value) {
        pushed = value;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
