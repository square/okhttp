package com.squareup.okhttp.internal;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class JettyNegoProviderTest {
    private InvocationHandler instance;
    private Class clazz;
    private List<String> peerProtocolList;

    @SuppressWarnings("unchecked")
    @Before
    public void init() throws Throwable {
        /**
         * I used Reflection for creation an instance of JettyNegoProvider because it is a static private class.
         */
        clazz = Class.forName("com.squareup.okhttp.internal.Platform$JettyNegoProvider");
        final Constructor constructor = clazz.getConstructor(List.class);
        peerProtocolList = new ArrayList<>();
        peerProtocolList.add("firstPeerProtocol");
        peerProtocolList.add("secondPeerProtocol");
        instance = (InvocationHandler) constructor.newInstance(peerProtocolList);
    }

    private Method getProviderMethod(String name) throws NoSuchMethodException {
        return StubALPNProvider.class.getMethod(name);
    }

    @Test
    public void invokeSupports() throws Throwable {
        Object supports = instance.invoke(null, getProviderMethod("supports"), new Object[0]);
        assertTrue((boolean) supports);
    }

    @Test
    public void invokeUnsupported() throws Throwable {
        Object unsupported = instance.invoke(null, getProviderMethod("unsupported"), new Object[0]);
        assertNull(unsupported);
        //check private field unsupported
        Field unsupportedField = clazz.getDeclaredField("unsupported");
        unsupportedField.setAccessible(true);
        assertTrue(unsupportedField.getBoolean(instance));
    }

    @Test
    public void invokeProtocols() throws Throwable {
        Object protocols = instance.invoke(null, getProviderMethod("protocols"), new Object[0]);
        assertEquals(protocols, peerProtocolList);
    }

    @Test
    public void invokeSelectProtocols() throws Throwable {
        //selectProtocol method
        List<String> servProtocol = new ArrayList<>();
        servProtocol.add("firstServerProtocol");

        //Check no intersection, will return peer's first protocol.
        Object selectProtocol = instance.invoke(null, getProviderMethod("selectProtocol"), new Object[]{servProtocol});
        assertEquals(selectProtocol, "firstPeerProtocol");

        //Check intersection, will return common protocol
        //make secondPeerProtocol common for peers and servers
        servProtocol.add("secondPeerProtocol");
        Object commonProtocol = instance.invoke(null, getProviderMethod("selectProtocol"), new Object[]{servProtocol});
        assertEquals(commonProtocol, "secondPeerProtocol");
    }

    @Test
    public void invokeSelect() throws Throwable {
        //select method
        List<String> servProtocol = new ArrayList<>();
        servProtocol.add("firstServerProtocol");

        //Check no intersection, will return peer's first protocol.
        Object selectProtocol = instance.invoke(null, getProviderMethod("select"), new Object[]{servProtocol});
        assertEquals(selectProtocol, "firstPeerProtocol");

        //Check intersection, will return common protocol
        //make secondPeerProtocol common for peers and servers
        servProtocol.add("secondPeerProtocol");
        Object commonProtocol = instance.invoke(null, getProviderMethod("select"), new Object[]{servProtocol});
        assertEquals(commonProtocol, "secondPeerProtocol");
    }

    @Test
    public void invokeProtocolSelected() throws Throwable {
        Object selectProtocol = instance.invoke(null, getProviderMethod("protocolSelected"), new String[]{"firstServerProtocol"});
        assertNull(selectProtocol);
        //check private field selected
        Field selectedField = clazz.getDeclaredField("selected");
        selectedField.setAccessible(true);
        assertEquals(selectedField.get(instance), "firstServerProtocol");
    }

    @Test
    public void invokeSelected() throws Throwable {
        Object selectProtocol = instance.invoke(null, getProviderMethod("selected"), new Object[]{"firstServerProtocol"});
        assertNull(selectProtocol);
        //check private field selected
        Field selectedField = clazz.getDeclaredField("selected");
        selectedField.setAccessible(true);
        assertEquals(selectedField.get(instance), "firstServerProtocol");
    }

    public static class StubALPNProvider {
        public boolean supports() {
            return true;
        }

        public void unsupported() {

        }

        public void protocols() {

        }

        public String selectProtocol() {
            return "";
        }


        public String select() {
            return "";
        }

        public void protocolSelected() {

        }

        public void selected() {

        }
    }
}