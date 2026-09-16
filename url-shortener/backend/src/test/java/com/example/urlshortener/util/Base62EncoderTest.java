package com.example.urlshortener.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Base62EncoderTest {

    @Test
    void encodesSmallValuesWithTheExpectedDigits() {
        assertThat(Base62Encoder.encode(0)).isEqualTo("0");
        assertThat(Base62Encoder.encode(9)).isEqualTo("9");
        assertThat(Base62Encoder.encode(10)).isEqualTo("A");
        assertThat(Base62Encoder.encode(61)).isEqualTo("z");
        assertThat(Base62Encoder.encode(62)).isEqualTo("10");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62, 3843, 1_000_000, 3_521_614_606_207L, Long.MAX_VALUE})
    void roundTripsThroughDecode(long value) {
        assertThat(Base62Encoder.decode(Base62Encoder.encode(value))).isEqualTo(value);
    }

    @Test
    void padsToTheRequestedWidthWithoutChangingTheValue() {
        String padded = Base62Encoder.encodePadded(62, 7);

        assertThat(padded).isEqualTo("0000010");
        assertThat(padded).hasSize(7);
        assertThat(Base62Encoder.decode(padded)).isEqualTo(62L);
    }

    @Test
    void leavesValuesLongerThanTheRequestedWidthAlone() {
        assertThat(Base62Encoder.encodePadded(Long.MAX_VALUE, 3)).hasSizeGreaterThan(3);
    }

    @Test
    void capacityGrowsByAFactorOfSixtyTwoPerCharacter() {
        assertThat(Base62Encoder.capacityFor(1)).isEqualTo(62L);
        assertThat(Base62Encoder.capacityFor(7)).isEqualTo(3_521_614_606_208L);
    }

    @Test
    void rejectsLengthsThatWouldOverflowALong() {
        assertThatThrownBy(() -> Base62Encoder.capacityFor(11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62Encoder.capacityFor(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeValuesAndNonBase62Text() {
        assertThatThrownBy(() -> Base62Encoder.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62Encoder.decode("abc-def")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62Encoder.decode("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void treatsCodesAsCaseSensitive() {
        assertThat(Base62Encoder.decode("aB")).isNotEqualTo(Base62Encoder.decode("Ab"));
    }

    @Test
    void validatesTheCharacterSet() {
        assertThat(Base62Encoder.isValidCode("aZ09")).isTrue();
        assertThat(Base62Encoder.isValidCode("has_underscore")).isFalse();
        assertThat(Base62Encoder.isValidCode("")).isFalse();
        assertThat(Base62Encoder.isValidCode(null)).isFalse();
    }
}
