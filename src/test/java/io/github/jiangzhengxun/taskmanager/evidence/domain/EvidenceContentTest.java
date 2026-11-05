package io.github.jiangzhengxun.taskmanager.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceContentTest {

    @Test
    void nullIsNotAString() {
        assertThatThrownBy(() -> EvidenceContent.normalize(null)).hasMessage(EvidenceContent.NOT_STRING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "　", " ", "   "})
    void blankAfterUnicodeTrimIsRejected(String raw) {
        assertThatThrownBy(() -> EvidenceContent.normalize(raw)).hasMessage(EvidenceContent.BLANK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "\uD800", "a\uDC00", "\uDC00\uD800", "x\uD83D"})
    void nulAndUnpairedSurrogatesAreRejected(String raw) {
        assertThatThrownBy(() -> EvidenceContent.normalize(raw)).hasMessage(EvidenceContent.UNSUPPORTED);
    }

    @Test
    void unsupportedCharacterCheckPrecedesBlankCheck() {
        assertThatThrownBy(() -> EvidenceContent.normalize(" \u0000 ")).hasMessage(EvidenceContent.UNSUPPORTED);
    }

    @Test
    void trimsEdgesKeepsInteriorWhitespaceAndNoNormalization() {
        assertThat(EvidenceContent.normalize("　 a \n b ")).isEqualTo("a \n b");
        assertThat(EvidenceContent.normalize("é")).isEqualTo("é");
    }

    @Test
    void lengthBoundaryCountsCodePoints() {
        String emoji = "😀";
        assertThat(EvidenceContent.normalize(emoji.repeat(2000))).isEqualTo(emoji.repeat(2000));
        assertThatThrownBy(() -> EvidenceContent.normalize(emoji.repeat(2001))).hasMessage(EvidenceContent.TOO_LONG);
        assertThat(EvidenceContent.normalize("a".repeat(2000))).hasSize(2000);
        assertThatThrownBy(() -> EvidenceContent.normalize("a".repeat(2001))).hasMessage(EvidenceContent.TOO_LONG);
        assertThat(EvidenceContent.normalize("  " + "a".repeat(2000) + "  ")).hasSize(2000);
    }
}
