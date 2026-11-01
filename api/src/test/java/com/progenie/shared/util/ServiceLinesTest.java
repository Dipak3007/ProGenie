package com.progenie.shared.util;

import java.util.List;

import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.ServiceLines.ServiceLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceLinesTest {

    @Test
    void readsItemsOrFallsBackToTheSingleService() {
        assertThat(ServiceLines.parse(null, "12:2, 15")).containsExactly(new ServiceLine(12L, 2), new ServiceLine(15L, 1));
        assertThat(ServiceLines.parse(7L, null)).containsExactly(new ServiceLine(7L, 1));
        assertThat(ServiceLines.of(7L, List.of(new ServiceLine(3L, 4)))).containsExactly(new ServiceLine(3L, 4));
        assertThat(ServiceLines.format(List.of(new ServiceLine(12L, 2), new ServiceLine(15L, 1)))).isEqualTo("12:2,15:1");
    }

    @Test
    void enforcesTheLimits() {
        assertThatThrownBy(() -> ServiceLines.parse(null, "a:b")).hasMessageContaining("12:2");
        assertThatThrownBy(() -> ServiceLines.of(null, null)).isInstanceOf(ApiException.class).hasMessageContaining("at least one");
        assertThatThrownBy(() -> ServiceLines.parse(null, "1,2,3,4,5,6")).hasMessageContaining("up to 5");
        assertThatThrownBy(() -> ServiceLines.parse(null, "1:6")).hasMessageContaining("1 to 5");
        assertThatThrownBy(() -> ServiceLines.parse(null, "1:0")).hasMessageContaining("1 to 5");
        assertThatThrownBy(() -> ServiceLines.parse(null, "1,1:2")).hasMessageContaining("once");
    }
}
