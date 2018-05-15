package org.aion.avm.core.shadowing;

import org.aion.avm.core.TestClassLoader;
import org.junit.Assert;
import org.junit.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ClassShadowingTest {

    private static void writeBytesToFile(byte[] bytes, String file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testReplaceJavaLang() throws IOException, ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        String name = "org.aion.avm.core.shadowing.TestResource";
        TestClassLoader loader = new TestClassLoader(TestResource.class.getClassLoader(), name, (inputBytes) -> {
            byte[] transformed = ClassShadowing.replaceJavaLang(Testing.CLASS_NAME, inputBytes);
            writeBytesToFile(transformed, "output.class");
            return transformed;
        });
        Class<?> clazz = loader.loadClass(name);
        Object obj = clazz.getConstructor().newInstance();

        Method method = clazz.getMethod("multi", int.class, int.class);
        Object ret = method.invoke(obj, 1, 2);
        Assert.assertEquals(0, ret);

        // Verify that we haven't created any wrapped instances, yet.
        Assert.assertEquals(0, Testing.countWrappedClasses);
        Assert.assertEquals(0, Testing.countWrappedClasses);
        
        // We can rely on our test-facing toString methods to look into what we got back.
        Object wrappedClass = clazz.getMethod("returnClass").invoke(obj);
        Assert.assertEquals("class org.aion.avm.java.lang.String", wrappedClass.toString());
        Object wrappedString = clazz.getMethod("returnString").invoke(obj);
        Assert.assertEquals("hello", wrappedString.toString());
        
        // Verify that we see wrapped instances.
        Assert.assertEquals(1, Testing.countWrappedClasses);
        Assert.assertEquals(1, Testing.countWrappedClasses);
    }


    public static class Testing {
        public static String CLASS_NAME = ClassShadowingTest.class.getCanonicalName().replaceAll("\\.", "/") + "$Testing";
        public static int countWrappedClasses;
        public static int countWrappedStrings;
        
        public static <T> org.aion.avm.java.lang.Class<T> wrapAsClass(Class<T> input) {
            countWrappedClasses += 1;
            return new org.aion.avm.java.lang.Class<T>(input);
        }
        public static org.aion.avm.java.lang.String wrapAsString(String input) {
            countWrappedStrings += 1;
            return new org.aion.avm.java.lang.String(input);
        }
    }
}