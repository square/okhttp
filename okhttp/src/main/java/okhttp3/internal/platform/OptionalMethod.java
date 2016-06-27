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

package okhttp3.internal.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Duck-typing for methods: Represents a method that may or may not be present on an object.
 *
 * @param <T> the type of the object the method might be on, typically an interface or base class
 */
class OptionalMethod<T> {

  /** The return type of the method. null means "don't care". */
  private final Class<?> returnType;

  private final String methodName;

  private final Class[] methodParams;

  /**
   * Creates an optional method.
   *
   * @param returnType the return type to required, null if it does not matter
   * @param methodName the name of the method
   * @param methodParams the method parameter types
   */
  public OptionalMethod(Class<?> returnType, String methodName, Class... methodParams) {
    this.returnType = returnType;
    this.methodName = methodName;
    this.methodParams = methodParams;
  }

  /**
   * Returns true if the method exists on the supplied {@code target}.
   */
  public boolean isSupported(T target) {
    return getMethod(target.getClass()) != null;
  }

  /**
   * Invokes the method on {@code target} with {@code args}. If the method does not exist or is not
   * public then {@code null} is returned. See also {@link #invokeOptionalWithoutCheckedException}.
   *
   * @throws IllegalArgumentException if the arguments are invalid
   * @throws InvocationTargetException if the invocation throws an exception
   */
  public Object invokeOptional(T target, Object... args) throws InvocationTargetException {
    Method m = getMethod(target.getClass());
    if (m == null) {
      return null;
    }
    try {
      return m.invoke(target, args);
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  /**
   * Invokes the method on {@code target}.  If the method does not exist or is not public then
   * {@code null} is returned. Any RuntimeException thrown by the method is thrown, checked
   * exceptions are wrapped in an {@link AssertionError}.
   *
   * @throws IllegalArgumentException if the arguments are invalid
   */
  public Object invokeOptionalWithoutCheckedException(T target, Object... args) {
    try {
      return invokeOptional(target, args);
    } catch (InvocationTargetException e) {
      Throwable targetException = e.getTargetException();
      if (targetException instanceof RuntimeException) {
        throw (RuntimeException) targetException;
      }
      AssertionError error = new AssertionError("Unexpected exception");
      error.initCause(targetException);
      throw error;
    }
  }

  /**
   * Invokes the method on {@code target} with {@code args}. Throws an error if the method is not
   * supported. See also {@link #invokeWithoutCheckedException(Object, Object...)}.
   *
   * @throws IllegalArgumentException if the arguments are invalid
   * @throws InvocationTargetException if the invocation throws an exception
   */
  public Object invoke(T target, Object... args) throws InvocationTargetException {
    Method m = getMethod(target.getClass());
    if (m == null) {
      throw new AssertionError("Method " + methodName + " not supported for object " + target);
    }
    try {
      return m.invoke(target, args);
    } catch (IllegalAccessException e) {
      // Method should be public: we checked.
      AssertionError error = new AssertionError("Unexpectedly could not call: " + m);
      error.initCause(e);
      throw error;
    }
  }

  /**
   * Invokes the method on {@code target}. Throws an error if the method is not supported. Any
   * RuntimeException thrown by the method is thrown, checked exceptions are wrapped in an {@link
   * AssertionError}.
   *
   * @throws IllegalArgumentException if the arguments are invalid
   */
  public Object invokeWithoutCheckedException(T target, Object... args) {
    try {
      return invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable targetException = e.getTargetException();
      if (targetException instanceof RuntimeException) {
        throw (RuntimeException) targetException;
      }
      AssertionError error = new AssertionError("Unexpected exception");
      error.initCause(targetException);
      throw error;
    }
  }

  /**
   * Perform a lookup for the method. No caching. In order to return a method the method name and
   * arguments must match those specified when the {@link OptionalMethod} was created. If the return
   * type is specified (i.e. non-null) it must also be compatible. The method must also be public.
   */
  private Method getMethod(Class<?> clazz) {
    Method method = null;
    if (methodName != null) {
      method = getPublicMethod(clazz, methodName, methodParams);
      if (method != null
          && returnType != null
          && !returnType.isAssignableFrom(method.getReturnType())) {

        // If the return type is non-null it must be compatible.
        method = null;
      }
    }
    return method;
  }

  private static Method getPublicMethod(Class<?> clazz, String methodName, Class[] parameterTypes) {
    Method method = null;
    try {
      method = clazz.getMethod(methodName, parameterTypes);
      if ((method.getModifiers() & Modifier.PUBLIC) == 0) {
        method = null;
      }
    } catch (NoSuchMethodException e) {
      // None.
    }
    return method;
  }
}

