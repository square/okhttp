package okhttp3;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.lang.reflect.Constructor;

@TargetClass(className = "jdk.internal.reflect.ReflectionFactory")
public final class TargetReflectionFactory {
    @Substitute
    private Constructor<?> generateConstructor(Class<?> cl, Constructor<?> constructorToCall) {
        return null;
    }
}