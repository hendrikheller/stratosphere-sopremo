/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/
package eu.stratosphere.sopremo.type;

import java.lang.reflect.Type;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.google.common.reflect.TypeToken;

import eu.stratosphere.util.Reference;
import eu.stratosphere.util.reflect.TypeHierarchyBrowser;
import eu.stratosphere.util.reflect.TypeHierarchyBrowser.Mode;
import eu.stratosphere.util.reflect.Visitor;

/**
 * @author arv
 */
public abstract class AbstractTypeMapper<M> {

	private final Map<Type, Map<Type, M>> mappers = new IdentityHashMap<Type, Map<Type, M>>();

	private final Map<Type, Type> defaultTypeMappings = new IdentityHashMap<Type, Type>();

	/**
	 * Initializes AbstractTypeMapper.
	 */
	public AbstractTypeMapper() {
		super();
	}

	protected void addMapper(Type from, Type target, M mapper) {
		Map<Type, M> fromMappers = this.mappers.get(from);
		if (fromMappers == null)
			this.mappers.put(from, fromMappers = new IdentityHashMap<Type, M>());
		fromMappers.put(target, mapper);
	}

	public Type getDefaultMappingType(final Type javaType) {
		Type type = this.defaultTypeMappings.get(javaType);
		if (type != null)
			return type;

		return findDefaultMappingType(javaType);
	}

	protected Type findDefaultMappingType(final Type javaType) {
		if (!(javaType instanceof Class<?>)) {
			Class<?> rawType = TypeToken.of(javaType).getRawType();
			Type rawJsonType = getDefaultMappingType(rawType);
			this.defaultTypeMappings.put(javaType, rawJsonType);
			return rawJsonType;
		}

		return findDefaultMappingType((Class<?>) javaType);
	}

	public Type getDefaultMappingType(final Class<?> fromClass) {
		final Type type = this.defaultTypeMappings.get(fromClass);
		if (type != null)
			return type;

		return findDefaultMappingType(fromClass);
	}

	protected Type findDefaultMappingType(final Class<?> fromClass) {
		final Type superClass = findRegisteredSuperclass(this.defaultTypeMappings.keySet(), fromClass);
		if (superClass != null) {
			final Type type = this.defaultTypeMappings.get(superClass);
			this.defaultTypeMappings.put(fromClass, type);
			return type;
		}
		return null;
	}

	protected static Type findRegisteredSuperclass(final Set<? extends Type> map, final Class<?> targetType) {
		for (Type type : map)
			if (TypeToken.of(type).isAssignableFrom(targetType))
				return type;
		return null;
	}

	protected static Type findRegisteredSubclass(final Set<? extends Type> map, final Class<?> targetType) {
		final TypeToken<?> token = TypeToken.of(targetType);
		for (Type type : map)
			if (token.isAssignableFrom(type))
				return type;
		return null;
	}

	public M getMapper(Class<? extends Object> fromClass, Type targetType) {
		final Class<?> rawTarget = TypeToken.of(targetType).getRawType();
		Map<Type, M> fromMappers = this.mappers.get(fromClass);
		if (fromMappers != null) {
			M targetMapper = fromMappers.get(targetType);
			if (targetMapper != null)
				return targetMapper;
		}
		M targetMapper = findMapperRecursively(fromClass, targetType, rawTarget);
		addMapper(fromClass, rawTarget, targetMapper);
		return targetMapper;
	}

	private M findMapperRecursively(final Class<? extends Object> fromClass,
			final Type targetType, final Class<?> rawTarget) {
		M targetMapper = findMapper(fromClass, fromClass, targetType, rawTarget);
		if (targetMapper != null)
			return targetMapper;

		final Reference<M> foundMapper = new Reference<M>();

		TypeHierarchyBrowser.INSTANCE.visit(fromClass, Mode.CLASS_FIRST,
			new Visitor<Class<?>>() {
				@Override
				public boolean visited(final Class<?> superClass, final int distance) {
					M mapper = findMapper(superClass, fromClass, targetType, rawTarget);

					if (mapper == null)
						return true;
					// found a matching coercer; terminate browsing
					foundMapper.setValue(mapper);
					return false;
				}
			});

		return foundMapper.getValue();
	}

	protected void addDefaultTypeMapping(Type from, Type to) {
		this.defaultTypeMappings.put(from, to);
	}

	protected M findMapper(Class<?> fromClass, Class<?> originalFromClass, Type targetType, Class<?> rawTarget) {

		Map<Type, M> fromMappers = this.mappers.get(fromClass);
		if (fromMappers == null) {
			// // copy mappers from superclass; e.g. IObjectNode -> Map defined but rawTarget is ObjectNode
			// final Class<?> rawFrom = TypeToken.of(fromClass).getRawType();
			// final Class<?> superClass = findRegisteredSuperclass(this.mappers.keySet(), rawFrom);
			// if (superClass == null)
			// return null;
			// fromMappers = addAllMappers(rawFrom, this.mappers.get(superClass));
			return null;
		}

		M targetMapper = fromMappers.get(rawTarget);
		if (targetMapper == null) {
			Type defaultType = getDefaultMappingType(originalFromClass);
			Class<?> rawDefaultType = defaultType == null ? null : TypeToken.of(defaultType).getRawType();
			if (rawDefaultType != null && rawDefaultType != rawTarget && rawTarget.isAssignableFrom(rawDefaultType)) {
				targetMapper = findMapperRecursively(originalFromClass, targetType, rawDefaultType);
			}
			else {
				final Type targetSuperClass = findRegisteredSubclass(fromMappers.keySet(), rawTarget);
				if (targetSuperClass != null) {
					targetMapper = fromMappers.get(targetSuperClass);
				}
			}
		}
		return targetMapper;
	}
}