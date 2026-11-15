package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PagingTest {

  @Test
  void defaultsTheLimitWhenAbsent() {
    assertThat(Paging.limit(null)).isEqualTo(500);
  }

  @Test
  void readsAGivenLimit() {
    assertThat(Paging.limit("1")).isEqualTo(1);
    assertThat(Paging.limit("1000")).isEqualTo(1000);
  }

  @Test
  void rejectsALimitOutsideOneToOneThousand() {
    assertThatThrownBy(() -> Paging.limit("0")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.limit("1001")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsALimitThatIsNotANumber() {
    assertThatThrownBy(() -> Paging.limit("")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.limit("ten")).isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void readsAStrictInteger() {
    assertThat(Paging.integer("page", "7", 1)).isEqualTo(7);
  }

  @Test
  void rejectsAnIntegerThatIsEmptyOrNotANumber() {
    assertThatThrownBy(() -> Paging.integer("page", "", 1))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.integer("page", "ten", 1))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsAnIntegerWrittenWithASignOrLeadingZero() {
    assertThatThrownBy(() -> Paging.integer("page", "+5", 1))
        .isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.integer("page", "05", 1))
        .isInstanceOf(InvalidRequestException.class);
  }

  @Test
  void rejectsAnIntegerBelowItsMinimum() {
    assertThatThrownBy(() -> Paging.integer("page", "0", 1))
        .isInstanceOf(InvalidRequestException.class);
    assertThat(Paging.integer("offset", "0", 0)).isZero();
  }

  @Test
  void pageSizeFollowsTheLimitRules() {
    assertThat(Paging.pageSize(null)).isEqualTo(500);
    assertThat(Paging.pageSize("1000")).isEqualTo(1000);
    assertThatThrownBy(() -> Paging.pageSize("0")).isInstanceOf(InvalidRequestException.class);
    assertThatThrownBy(() -> Paging.pageSize("1001")).isInstanceOf(InvalidRequestException.class);
  }
}
