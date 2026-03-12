package io.github.seanchatmangpt.jotp.test;

import io.github.seanchatmangpt.jotp.Maths;
import io.github.seanchatmangpt.jotp.Maths.Input;
import io.github.seanchatmangpt.jotp.Maths.Output;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class MathsTest implements WithAssertions {

    @Test
    void test() {
        assertThat(new Maths().sum(new Input(1, 2))).isEqualTo(new Output(3));
    }
}
