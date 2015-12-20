/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package okhttp3.internal;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link OptionalMethod}.
 */
public class OptionalMethodTest {
  @SuppressWarnings("unused")
  private static class BaseClass {
    public String stringMethod() {
      return "string";
    }

    public void voidMethod() {}
  }

  @SuppressWarnings("unused")
  private static class SubClass1 extends BaseClass {
    public String subclassMethod() {
      return "subclassMethod1";
    }

    public String methodWithArgs(String arg) {
      return arg;
    }
  }

  @SuppressWarnings("unused")
  private static class SubClass2 extends BaseClass {
    public int subclassMethod() {
      return 1234;
    }

    public String methodWithArgs(String arg) {
      return arg;
    }

    public void throwsException() throws IOException {
      throw new IOException();
    }

    public void throwsRuntimeException() throws Exception {
      throw new NumberFormatException();
    }

    protected void nonPublic() {}
  }

  private final static OptionalMethod<BaseClass> STRING_METHOD_RETURNS_ANY =
      new OptionalMethod<BaseClass>(null, "stringMethod");
  private final static OptionalMethod<BaseClass> STRING_METHOD_RETURNS_STRING =
      new OptionalMethod<BaseClass>(String.class, "stringMethod");
  private final static OptionalMethod<BaseClass> STRING_METHOD_RETURNS_INT =
      new OptionalMethod<BaseClass>(Integer.TYPE, "stringMethod");
  private final static OptionalMethod<BaseClass> VOID_METHOD_RETURNS_ANY =
      new OptionalMethod<BaseClass>(null, "voidMethod");
  private final static OptionalMethod<BaseClass> VOID_METHOD_RETURNS_VOID =
      new OptionalMethod<BaseClass>(Void.TYPE, "voidMethod");
  private final static OptionalMethod<BaseClass> SUBCLASS_METHOD_RETURNS_ANY =
      new OptionalMethod<BaseClass>(null, "subclassMethod");
  private final static OptionalMethod<BaseClass> SUBCLASS_METHOD_RETURNS_STRING =
      new OptionalMethod<BaseClass>(String.class, "subclassMethod");
  private final static OptionalMethod<BaseClass> SUBCLASS_METHOD_RETURNS_INT =
      new OptionalMethod<BaseClass>(Integer.TYPE, "subclassMethod");
  private final static OptionalMethod<BaseClass> METHOD_WITH_ARGS_WRONG_PARAMS =
      new OptionalMethod<BaseClass>(null, "methodWithArgs", Integer.class);
  private final static OptionalMethod<BaseClass> METHOD_WITH_ARGS_CORRECT_PARAMS =
      new OptionalMethod<BaseClass>(null, "methodWithArgs", String.class);

  private final static OptionalMethod<BaseClass> THROWS_EXCEPTION =
      new OptionalMethod<BaseClass>(null, "throwsException");
  private final static OptionalMethod<BaseClass> THROWS_RUNTIME_EXCEPTION =
      new OptionalMethod<BaseClass>(null, "throwsRuntimeException");
  private final static OptionalMethod<BaseClass> NON_PUBLIC =
      new OptionalMethod<BaseClass>(null, "nonPublic");

  @Test
  public void isSupported() throws Exception {
    {
      BaseClass base = new BaseClass();
      assertTrue(STRING_METHOD_RETURNS_ANY.isSupported(base));
      assertTrue(STRING_METHOD_RETURNS_STRING.isSupported(base));
      assertFalse(STRING_METHOD_RETURNS_INT.isSupported(base));
      assertTrue(VOID_METHOD_RETURNS_ANY.isSupported(base));
      assertTrue(VOID_METHOD_RETURNS_VOID.isSupported(base));
      assertFalse(SUBCLASS_METHOD_RETURNS_ANY.isSupported(base));
      assertFalse(SUBCLASS_METHOD_RETURNS_STRING.isSupported(base));
      assertFalse(SUBCLASS_METHOD_RETURNS_INT.isSupported(base));
      assertFalse(METHOD_WITH_ARGS_WRONG_PARAMS.isSupported(base));
      assertFalse(METHOD_WITH_ARGS_CORRECT_PARAMS.isSupported(base));
    }
    {
      SubClass1 subClass1 = new SubClass1();
      assertTrue(STRING_METHOD_RETURNS_ANY.isSupported(subClass1));
      assertTrue(STRING_METHOD_RETURNS_STRING.isSupported(subClass1));
      assertFalse(STRING_METHOD_RETURNS_INT.isSupported(subClass1));
      assertTrue(VOID_METHOD_RETURNS_ANY.isSupported(subClass1));
      assertTrue(VOID_METHOD_RETURNS_VOID.isSupported(subClass1));
      assertTrue(SUBCLASS_METHOD_RETURNS_ANY.isSupported(subClass1));
      assertTrue(SUBCLASS_METHOD_RETURNS_STRING.isSupported(subClass1));
      assertFalse(SUBCLASS_METHOD_RETURNS_INT.isSupported(subClass1));
      assertFalse(METHOD_WITH_ARGS_WRONG_PARAMS.isSupported(subClass1));
      assertTrue(METHOD_WITH_ARGS_CORRECT_PARAMS.isSupported(subClass1));
    }
    {
      SubClass2 subClass2 = new SubClass2();
      assertTrue(STRING_METHOD_RETURNS_ANY.isSupported(subClass2));
      assertTrue(STRING_METHOD_RETURNS_STRING.isSupported(subClass2));
      assertFalse(STRING_METHOD_RETURNS_INT.isSupported(subClass2));
      assertTrue(VOID_METHOD_RETURNS_ANY.isSupported(subClass2));
      assertTrue(VOID_METHOD_RETURNS_VOID.isSupported(subClass2));
      assertTrue(SUBCLASS_METHOD_RETURNS_ANY.isSupported(subClass2));
      assertFalse(SUBCLASS_METHOD_RETURNS_STRING.isSupported(subClass2));
      assertTrue(SUBCLASS_METHOD_RETURNS_INT.isSupported(subClass2));
      assertFalse(METHOD_WITH_ARGS_WRONG_PARAMS.isSupported(subClass2));
      assertTrue(METHOD_WITH_ARGS_CORRECT_PARAMS.isSupported(subClass2));
    }
  }

  @Test
  public void invoke() throws Exception {
    {
      BaseClass base = new BaseClass();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invoke(base));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invoke(base));
      assertErrorOnInvoke(STRING_METHOD_RETURNS_INT, base);
      assertNull(VOID_METHOD_RETURNS_ANY.invoke(base));
      assertNull(VOID_METHOD_RETURNS_VOID.invoke(base));
      assertErrorOnInvoke(SUBCLASS_METHOD_RETURNS_ANY, base);
      assertErrorOnInvoke(SUBCLASS_METHOD_RETURNS_STRING, base);
      assertErrorOnInvoke(SUBCLASS_METHOD_RETURNS_INT, base);
      assertErrorOnInvoke(METHOD_WITH_ARGS_WRONG_PARAMS, base);
      assertErrorOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, base);
    }
    {
      SubClass1 subClass1 = new SubClass1();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invoke(subClass1));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invoke(subClass1));
      assertErrorOnInvoke(STRING_METHOD_RETURNS_INT, subClass1);
      assertNull(VOID_METHOD_RETURNS_ANY.invoke(subClass1));
      assertNull(VOID_METHOD_RETURNS_VOID.invoke(subClass1));
      assertEquals("subclassMethod1", SUBCLASS_METHOD_RETURNS_ANY.invoke(subClass1));
      assertEquals("subclassMethod1", SUBCLASS_METHOD_RETURNS_STRING.invoke(subClass1));
      assertErrorOnInvoke(SUBCLASS_METHOD_RETURNS_INT, subClass1);
      assertErrorOnInvoke(METHOD_WITH_ARGS_WRONG_PARAMS, subClass1);
      assertEquals("arg", METHOD_WITH_ARGS_CORRECT_PARAMS.invoke(subClass1, "arg"));
    }

    {
      SubClass2 subClass2 = new SubClass2();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invoke(subClass2));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invoke(subClass2));
      assertErrorOnInvoke(STRING_METHOD_RETURNS_INT, subClass2);
      assertNull(VOID_METHOD_RETURNS_ANY.invoke(subClass2));
      assertNull(VOID_METHOD_RETURNS_VOID.invoke(subClass2));
      assertEquals(1234, SUBCLASS_METHOD_RETURNS_ANY.invoke(subClass2));
      assertErrorOnInvoke(SUBCLASS_METHOD_RETURNS_STRING, subClass2);
      assertEquals(1234, SUBCLASS_METHOD_RETURNS_INT.invoke(subClass2));
      assertErrorOnInvoke(METHOD_WITH_ARGS_WRONG_PARAMS, subClass2);
      assertEquals("arg", METHOD_WITH_ARGS_CORRECT_PARAMS.invoke(subClass2, "arg"));
    }
  }

  @Test
  public void invokeBadArgs() throws Exception {
    SubClass1 subClass1 = new SubClass1();
    assertIllegalArgumentExceptionOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1); // no args
    assertIllegalArgumentExceptionOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, 123);
    assertIllegalArgumentExceptionOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, true);
    assertIllegalArgumentExceptionOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, new Object());
    assertIllegalArgumentExceptionOnInvoke(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, "one", "two");
  }

  @Test
  public void invokeWithException() throws Exception {
    SubClass2 subClass2 = new SubClass2();
    try {
      THROWS_EXCEPTION.invoke(subClass2);
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getTargetException() instanceof IOException);
    }

    try {
      THROWS_RUNTIME_EXCEPTION.invoke(subClass2);
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getTargetException() instanceof NumberFormatException);
    }
  }

  @Test
  public void invokeNonPublic() throws Exception {
    SubClass2 subClass2 = new SubClass2();
    assertFalse(NON_PUBLIC.isSupported(subClass2));
    assertErrorOnInvoke(NON_PUBLIC, subClass2);
  }

  @Test
  public void invokeOptional() throws Exception {
    {
      BaseClass base = new BaseClass();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invokeOptional(base));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invokeOptional(base));
      assertNull(STRING_METHOD_RETURNS_INT.invokeOptional(base));
      assertNull(VOID_METHOD_RETURNS_ANY.invokeOptional(base));
      assertNull(VOID_METHOD_RETURNS_VOID.invokeOptional(base));
      assertNull(SUBCLASS_METHOD_RETURNS_ANY.invokeOptional(base));
      assertNull(SUBCLASS_METHOD_RETURNS_STRING.invokeOptional(base));
      assertNull(SUBCLASS_METHOD_RETURNS_INT.invokeOptional(base));
      assertNull(METHOD_WITH_ARGS_WRONG_PARAMS.invokeOptional(base));
      assertNull(METHOD_WITH_ARGS_CORRECT_PARAMS.invokeOptional(base));
    }
    {
      SubClass1 subClass1 = new SubClass1();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invokeOptional(subClass1));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invokeOptional(subClass1));
      assertNull(STRING_METHOD_RETURNS_INT.invokeOptional(subClass1));
      assertNull(VOID_METHOD_RETURNS_ANY.invokeOptional(subClass1));
      assertNull(VOID_METHOD_RETURNS_VOID.invokeOptional(subClass1));
      assertEquals("subclassMethod1", SUBCLASS_METHOD_RETURNS_ANY.invokeOptional(subClass1));
      assertEquals("subclassMethod1", SUBCLASS_METHOD_RETURNS_STRING.invokeOptional(subClass1));
      assertNull(SUBCLASS_METHOD_RETURNS_INT.invokeOptional(subClass1));
      assertNull(METHOD_WITH_ARGS_WRONG_PARAMS.invokeOptional(subClass1));
      assertEquals("arg", METHOD_WITH_ARGS_CORRECT_PARAMS.invokeOptional(subClass1, "arg"));
    }

    {
      SubClass2 subClass2 = new SubClass2();
      assertEquals("string", STRING_METHOD_RETURNS_STRING.invokeOptional(subClass2));
      assertEquals("string", STRING_METHOD_RETURNS_ANY.invokeOptional(subClass2));
      assertNull(STRING_METHOD_RETURNS_INT.invokeOptional(subClass2));
      assertNull(VOID_METHOD_RETURNS_ANY.invokeOptional(subClass2));
      assertNull(VOID_METHOD_RETURNS_VOID.invokeOptional(subClass2));
      assertEquals(1234, SUBCLASS_METHOD_RETURNS_ANY.invokeOptional(subClass2));
      assertNull(SUBCLASS_METHOD_RETURNS_STRING.invokeOptional(subClass2));
      assertEquals(1234, SUBCLASS_METHOD_RETURNS_INT.invokeOptional(subClass2));
      assertNull(METHOD_WITH_ARGS_WRONG_PARAMS.invokeOptional(subClass2));
      assertEquals("arg", METHOD_WITH_ARGS_CORRECT_PARAMS.invokeOptional(subClass2, "arg"));
    }
  }

  @Test
  public void invokeOptionalBadArgs() throws Exception {
    SubClass1 subClass1 = new SubClass1();
    assertIllegalArgumentExceptionOnInvokeOptional(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1); // no args
    assertIllegalArgumentExceptionOnInvokeOptional(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, 123);
    assertIllegalArgumentExceptionOnInvokeOptional(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, true);
    assertIllegalArgumentExceptionOnInvokeOptional(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, new Object());
    assertIllegalArgumentExceptionOnInvokeOptional(METHOD_WITH_ARGS_CORRECT_PARAMS, subClass1, "one", "two");
  }

  @Test
  public void invokeOptionalWithException() throws Exception {
    SubClass2 subClass2 = new SubClass2();
    try {
      THROWS_EXCEPTION.invokeOptional(subClass2);
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getTargetException() instanceof IOException);
    }

    try {
      THROWS_RUNTIME_EXCEPTION.invokeOptional(subClass2);
    } catch (InvocationTargetException expected) {
      assertTrue(expected.getTargetException() instanceof NumberFormatException);
    }
  }

  @Test
  public void invokeOptionalNonPublic() throws Exception {
    SubClass2 subClass2 = new SubClass2();
    assertFalse(NON_PUBLIC.isSupported(subClass2));
    assertErrorOnInvokeOptional(NON_PUBLIC, subClass2);
  }

  private static <T> void assertErrorOnInvoke(
      OptionalMethod<T> optionalMethod, T base, Object... args) throws Exception {
    try {
      optionalMethod.invoke(base, args);
      fail();
    } catch (Error expected) {
    }
  }

  private static <T> void assertIllegalArgumentExceptionOnInvoke(
      OptionalMethod<T> optionalMethod, T base, Object... args) throws Exception {
    try {
      optionalMethod.invoke(base, args);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  private static <T> void assertErrorOnInvokeOptional(
      OptionalMethod<T> optionalMethod, T base, Object... args) throws Exception {
    try {
      optionalMethod.invokeOptional(base, args);
      fail();
    } catch (Error expected) {
    }
  }

  private static <T> void assertIllegalArgumentExceptionOnInvokeOptional(
      OptionalMethod<T> optionalMethod, T base, Object... args) throws Exception {
    try {
      optionalMethod.invokeOptional(base, args);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }
}
