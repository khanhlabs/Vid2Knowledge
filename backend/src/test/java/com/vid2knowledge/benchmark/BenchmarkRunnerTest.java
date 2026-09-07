package com.vid2knowledge.benchmark;

import com.vid2knowledge.common.exception.InvalidYoutubeUrlException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkRunnerTest {
    @Test
    void decisionRunRequiresFiftyUniqueRightsAttestedSupportedVideos() {
        List<String> formats = List.of("lecture", "podcast", "screen-demo", "slides");
        var cases = IntStream.range(0, 50).mapToObj(index -> datasetRun(
                index, "run-" + index, formats.get(index % formats.size())
        )).toList();
        var repeats = IntStream.range(0, 10).mapToObj(index -> datasetRun(
                index, "repeat-" + index, formats.get(index % formats.size())
        )).toList();
        var valid = Stream.concat(cases.stream(), repeats.stream()).toList();
        BenchmarkRunner.validateDataset(new BenchmarkRunner.Dataset("benchmark-v1", valid), false);

        assertThatThrownBy(() -> BenchmarkRunner.validateDataset(
                new BenchmarkRunner.Dataset("benchmark-v1", cases.subList(0, 49)), false
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 50");
        assertThatThrownBy(() -> BenchmarkRunner.validateDataset(
                new BenchmarkRunner.Dataset("benchmark-v1", List.of(new BenchmarkRunner.DatasetRun(
                        "case-1", "run-1", "https://example.com/video", 600, "permission",
                        "vi", "lecture", "clear speech", true
                ))), true
        )).isInstanceOf(InvalidYoutubeUrlException.class);
        assertThatThrownBy(() -> BenchmarkRunner.validateDataset(
                new BenchmarkRunner.Dataset("benchmark-v1", List.of(new BenchmarkRunner.DatasetRun(
                        "case-1", "run-1", "https://youtu.be/abcdefghijk", 600, "",
                        "vi", "lecture", "clear speech", true
                ))), true
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rightsBasis");
    }

    private static BenchmarkRunner.DatasetRun datasetRun(int index, String runId, String format) {
        long duration = switch (index % 3) {
            case 0 -> 600;
            case 1 -> 1_800;
            default -> 5_400;
        };
        return new BenchmarkRunner.DatasetRun(
                "case-" + index, runId, "https://youtu.be/" + String.format("v%010d", index), duration,
                "Written permission retained outside repository", index % 2 == 0 ? "vi" : "en",
                format, "Representative audio and visual characteristics", index % 3 == 0
        );
    }
}
