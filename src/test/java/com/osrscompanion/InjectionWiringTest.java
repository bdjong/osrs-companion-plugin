package com.osrscompanion;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Every plugin class RuneLite's injector builds (the plugin's @Inject fields and, from there, the
 * parameters of @Inject constructors) needs exactly one @Inject constructor or a non-private
 * no-argument one; otherwise the plugin fails to start.
 */
public class InjectionWiringTest {
	@Test
	public void everyInjectedPluginClassHasAnInjectableConstructor() {
		List<String> problems = new ArrayList<>();
		Deque<Class<?>> todo = new ArrayDeque<>();
		Set<Class<?>> seen = new HashSet<>();
		for (Field f : OSRSCompanionPlugin.class.getDeclaredFields()) {
			if (isInject(f.getDeclaredAnnotations())) {
				todo.add(f.getType());
			}
		}
		while (!todo.isEmpty()) {
			Class<?> c = todo.poll();
			if (!c.getName().startsWith("com.osrscompanion.") || c.isInterface() || c.isEnum() || !seen.add(c)) {
				continue;
			}
			List<Constructor<?>> injected = new ArrayList<>();
			boolean noArg = false;
			for (Constructor<?> k : c.getDeclaredConstructors()) {
				if (isInject(k.getDeclaredAnnotations())) {
					injected.add(k);
				} else if (k.getParameterCount() == 0 && !Modifier.isPrivate(k.getModifiers())) {
					noArg = true;
				}
			}
			if (injected.size() > 1 || (injected.isEmpty() && !noArg)) {
				problems.add(c.getSimpleName());
			}
			for (Constructor<?> k : injected) {
				for (Class<?> p : k.getParameterTypes()) {
					todo.add(p);
				}
			}
			for (Field f : c.getDeclaredFields()) {
				if (isInject(f.getDeclaredAnnotations())) {
					todo.add(f.getType());
				}
			}
		}
		assertTrue("No injectable constructor: " + problems, problems.isEmpty());
		assertTrue("LiveActivityMonitor should be reached", seen.contains(LiveActivityMonitor.class));
	}

	private static boolean isInject(java.lang.annotation.Annotation[] annotations) {
		for (java.lang.annotation.Annotation a : annotations) {
			if (a.annotationType().getSimpleName().equals("Inject")) {
				return true;
			}
		}
		return false;
	}
}
