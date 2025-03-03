package me.bechberger.meta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class MockitoTest {

  @Mock
  List<String> mockedList;

  @Test
  public void whenNotUseMockAnnotation_thenCorrect() throws InterruptedException {
    mockedList.add("one");
    Mockito.verify(mockedList).add("one");
    assertEquals(0, mockedList.size());

    Mockito.when(mockedList.size()).thenReturn(100);
    assertEquals(100, mockedList.size());

    Function<Integer, String> function = (i) -> "Mockito" + i;
    assertEquals("Mockito3", function.apply(3));

    Thread.sleep(10000000L);
  }
}
