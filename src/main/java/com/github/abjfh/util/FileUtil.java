package com.github.abjfh.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

public class FileUtil {
    public static String DEFAULT_SEPARATOR = ",";

    public static List<String[]> loadCsvFile(String fileName) throws IOException {
        return loadCsvFile(fileName, DEFAULT_SEPARATOR);
    }

    public static List<String[]> loadCsvFile(String fileName, String separator) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(Paths.get(fileName))) {
            return br.lines()
                    .parallel()
                    .map(line -> line.split(separator))
                    .collect(Collectors.toList());
        }
    }
}
