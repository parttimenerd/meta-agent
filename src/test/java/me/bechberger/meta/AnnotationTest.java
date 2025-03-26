package me.bechberger.meta;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@AnnotationTest.Annotation(key = "key", value = "value")
public class AnnotationTest {

    @Retention(RetentionPolicy.RUNTIME)
    @interface Annotation {
        String key();
        String value();
    }

    @Test
    public void testAnnotation() throws InterruptedException {
        Annotation annotation = AnnotationTest.class.getAnnotation(Annotation.class);
        System.out.println(annotation.key());
        System.out.println(annotation.value());
        Thread.sleep(1000000);
    }
}
