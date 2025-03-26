package me.bechberger.meta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
public class MockitoTest {

  @Mock
  List<String> mockedList;

  @Test
  public void testList() throws InterruptedException {
    mockedList.add("one");
    Mockito.verify(mockedList).add("one");

    Thread.sleep(10000000L);
  }
}
