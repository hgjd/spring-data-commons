/*
 * Copyright 2017-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.mapping.model;

import kotlin.reflect.KFunction;
import kotlin.reflect.KParameter;
import kotlin.reflect.jvm.ReflectJvmMapping;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.data.mapping.Parameter;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.PreferredConstructor;
import org.springframework.data.util.KotlinReflectionUtils;
import org.springframework.data.util.ReflectionUtils;
import org.springframework.lang.Nullable;

/**
 * Kotlin-specific extension to {@link ClassGeneratingEntityInstantiator} that adapts Kotlin constructors with
 * defaulting.
 *
 * @author Mark Paluch
 * @author Oliver Gierke
 * @since 2.0
 */
class KotlinClassGeneratingEntityInstantiator extends ClassGeneratingEntityInstantiator {

	@Override
	protected EntityInstantiator doCreateEntityInstantiator(PersistentEntity<?, ?> entity) {

		var constructor = entity.getPersistenceConstructor();

		if (KotlinReflectionUtils.isSupportedKotlinClass(entity.getType()) && constructor != null) {

			var defaultConstructor = new DefaultingKotlinConstructorResolver(entity)
					.getDefaultConstructor();

			if (defaultConstructor != null) {

				var instantiator = createObjectInstantiator(entity, defaultConstructor);

				return new DefaultingKotlinClassInstantiatorAdapter(instantiator, constructor);
			}
		}

		return super.doCreateEntityInstantiator(entity);
	}

	/**
	 * Resolves a {@link PreferredConstructor} to a synthetic Kotlin constructor accepting the same user-space parameters
	 * suffixed by Kotlin-specifics required for defaulting and the {@code kotlin.jvm.internal.DefaultConstructorMarker}.
	 *
	 * @since 2.0
	 * @author Mark Paluch
	 */
	static class DefaultingKotlinConstructorResolver {

		private final @Nullable PreferredConstructor<?, ?> defaultConstructor;

		@SuppressWarnings("unchecked")
		DefaultingKotlinConstructorResolver(PersistentEntity<?, ?> entity) {

			var hit = resolveDefaultConstructor(entity);
			var persistenceConstructor = entity.getPersistenceConstructor();

			if (hit != null && persistenceConstructor != null) {
				this.defaultConstructor = new PreferredConstructor<>(hit,
						persistenceConstructor.getParameters().toArray(new Parameter[0]));
			} else {
				this.defaultConstructor = null;
			}
		}

		@Nullable
		private static Constructor<?> resolveDefaultConstructor(PersistentEntity<?, ?> entity) {

			var persistenceConstructor = entity.getPersistenceConstructor();

			if (persistenceConstructor == null) {
				return null;
			}

			Constructor<?> hit = null;
			var constructor = persistenceConstructor.getConstructor();

			for (var candidate : entity.getType().getDeclaredConstructors()) {

				// use only synthetic constructors
				if (!candidate.isSynthetic()) {
					continue;
				}

				// candidates must contain at least two additional parameters (int, DefaultConstructorMarker).
				// Number of defaulting masks derives from the original constructor arg count
				var syntheticParameters = KotlinDefaultMask.getMaskCount(constructor.getParameterCount())
						+ /* DefaultConstructorMarker */ 1;

				if (constructor.getParameterCount() + syntheticParameters != candidate.getParameterCount()) {
					continue;
				}

				var constructorParameters = constructor.getParameters();
				var candidateParameters = candidate.getParameters();

				if (!candidateParameters[candidateParameters.length - 1].getType().getName()
						.equals("kotlin.jvm.internal.DefaultConstructorMarker")) {
					continue;
				}

				if (parametersMatch(constructorParameters, candidateParameters)) {
					hit = candidate;
					break;
				}
			}

			return hit;
		}

		private static boolean parametersMatch(java.lang.reflect.Parameter[] constructorParameters,
				java.lang.reflect.Parameter[] candidateParameters) {

			return IntStream.range(0, constructorParameters.length)
					.allMatch(i -> constructorParameters[i].getType().equals(candidateParameters[i].getType()));
		}

		@Nullable
		PreferredConstructor<?, ?> getDefaultConstructor() {
			return defaultConstructor;
		}
	}

	/**
	 * Entity instantiator for Kotlin constructors that apply parameter defaulting. Kotlin constructors that apply
	 * argument defaulting are marked with {@link kotlin.jvm.internal.DefaultConstructorMarker} and accept additional
	 * parameters besides the regular (user-space) parameters. Additional parameters are:
	 * <ul>
	 * <li>defaulting bitmask ({@code int}), a bit mask slot for each 32 parameters</li>
	 * <li>{@code DefaultConstructorMarker} (usually null)</li>
	 * </ul>
	 * <strong>Defaulting bitmask</strong>
	 * <p>
	 * The defaulting bitmask is a 32 bit integer representing which positional argument should be defaulted. Defaulted
	 * arguments are passed as {@literal null} and require the appropriate positional bit set ( {@code 1 << 2} for the 2.
	 * argument)). Since the bitmask represents only 32 bit states, it requires additional masks (slots) if more than 32
	 * arguments are represented.
	 *
	 * @author Mark Paluch
	 * @since 2.0
	 */
	static class DefaultingKotlinClassInstantiatorAdapter implements EntityInstantiator {

		private final ObjectInstantiator instantiator;
		private final KFunction<?> constructor;
		private final List<KParameter> kParameters;
		private final Constructor<?> synthetic;

		DefaultingKotlinClassInstantiatorAdapter(ObjectInstantiator instantiator, PreferredConstructor<?, ?> constructor) {

			var kotlinConstructor = ReflectJvmMapping.getKotlinFunction(constructor.getConstructor());

			if (kotlinConstructor == null) {
				throw new IllegalArgumentException(
						"No corresponding Kotlin constructor found for " + constructor.getConstructor());
			}

			this.instantiator = instantiator;
			this.constructor = kotlinConstructor;
			this.kParameters = kotlinConstructor.getParameters();
			this.synthetic = constructor.getConstructor();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T, E extends PersistentEntity<? extends T, P>, P extends PersistentProperty<P>> T createInstance(E entity,
				ParameterValueProvider<P> provider) {

			var params = extractInvocationArguments(entity.getPersistenceConstructor(), provider);

			try {
				return (T) instantiator.newInstance(params);
			} catch (Exception e) {
				throw new MappingInstantiationException(entity, Arrays.asList(params), e);
			}
		}

		private <P extends PersistentProperty<P>, T> Object[] extractInvocationArguments(
				@Nullable PreferredConstructor<? extends T, P> preferredConstructor, ParameterValueProvider<P> provider) {

			if (preferredConstructor == null) {
				throw new IllegalArgumentException("PreferredConstructor must not be null!");
			}

			var params = allocateArguments(synthetic.getParameterCount()
					+ KotlinDefaultMask.getMaskCount(synthetic.getParameterCount()) + /* DefaultConstructorMarker */1);
			var userParameterCount = kParameters.size();

			var parameters = preferredConstructor.getParameters();

			// Prepare user-space arguments
			for (var i = 0; i < userParameterCount; i++) {

				var parameter = parameters.get(i);
				params[i] = provider.getParameterValue(parameter);
			}

			var defaultMask = KotlinDefaultMask.from(constructor, it -> {

				var index = kParameters.indexOf(it);

				var parameter = parameters.get(index);
				var type = parameter.getType().getType();

				if (it.isOptional() && params[index] == null) {
					if (type.isPrimitive()) {

						// apply primitive defaulting to prevent NPE on primitive downcast
						params[index] = ReflectionUtils.getPrimitiveDefault(type);
					}
					return false;
				}

				return true;
			});

			var defaulting = defaultMask.getDefaulting();
			// append nullability masks to creation arguments
			for (var i = 0; i < defaulting.length; i++) {
				params[userParameterCount + i] = defaulting[i];
			}

			return params;
		}
	}
}
