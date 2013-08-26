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

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;

import eu.stratosphere.sopremo.type.typed.ITypedObjectNode;
import eu.stratosphere.util.CollectionUtil;
import eu.stratosphere.util.reflect.ReflectUtil;

/**
 * @author arv
 */
public class JsonToJavaMapper extends AbstractTypeMapper<TypeMapper<?, ?>> {
	/**
	 * @author arv
	 */
	@SuppressWarnings("rawtypes")
	private static final class ArrayToArrayMapper extends TypeMapper<IArrayNode, Object> {
		private final Class<?> rawElemType;

		private final Type elemType;

		/**
		 * Initializes ArrayToArrayMapper.
		 * 
		 * @param defaultType
		 */
		private ArrayToArrayMapper(Type targetType) {
			super(null);
			this.elemType = targetType instanceof Class ? ((Class<?>) targetType).getComponentType()
				: ((GenericArrayType) targetType).getGenericComponentType();
			this.rawElemType =
				this.elemType instanceof Class ? (Class) this.elemType : TypeToken.of(this.elemType).getRawType();
		}

		/*
		 * (non-Javadoc)
		 * @see eu.stratosphere.sopremo.type.JsonToJavaMapper.Mapper#mapTo(java.lang.Object, java.lang.Object,
		 * java.lang.reflect.Type)
		 */
		@Override
		public Object mapTo(IArrayNode from, Object target) {
			final int fromSize = from.size();

			if (target == null || fromSize != Array.getLength(from))
				target = Array.newInstance(this.rawElemType, fromSize);

			for (int index = 0; index < fromSize; index++)
				Array.set(target, index, INSTANCE.map(from.get(index), Array.get(target, index), this.elemType));
			return target;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final class ObjectToMapMapper extends TypeMapper<IObjectNode, Map> {
		private final Type valueType, keyType;

		private final Class<?> rawKeyType;

		/**
		 * Initializes JsonToJavaMapper.ObjectToMapMapper.
		 */
		public ObjectToMapMapper(Type targetType) {
			super(HashMap.class);
			if (targetType instanceof ParameterizedType) {
				this.keyType = ((ParameterizedType) targetType).getActualTypeArguments()[0];
				this.valueType = ((ParameterizedType) targetType).getActualTypeArguments()[1];
				this.rawKeyType = TypeToken.of(this.keyType).getRawType();
			} else {
				this.keyType = this.rawKeyType = String.class;
				this.valueType = Object.class;
			}
		}

		private final ThreadLocal<Set> reusedKeys = new ThreadLocal<Set>() {
			@Override
			protected Set initialValue() {
				return new HashSet();
			};
		};

		/*
		 * (non-Javadoc)
		 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
		 */
		@SuppressWarnings("unchecked")
		@Override
		public Map mapTo(IObjectNode from, Map target) {
			Set reusedKeys = this.reusedKeys.get();
			if (this.rawKeyType == String.class) {
				reusedKeys.addAll(from.getFieldNames());
				for (String key : from.getFieldNames())
					target.put(key, INSTANCE.map(from.get(key), target.get(key), this.valueType));
			}
			else {
				for (String key : from.getFieldNames()) {
					final Object targetKey = INSTANCE.map(from.get(key), null, this.keyType);
					target.put(targetKey, INSTANCE.map(from.get(key), target.get(key), this.valueType));
					reusedKeys.add(targetKey);
				}
			}

			Iterables.retainAll(target.keySet(), reusedKeys);
			reusedKeys.clear();
			return target;
		}
	}

	@SuppressWarnings("rawtypes")
	private static final class ArrayToListMapper extends TypeMapper<IArrayNode, List> {
		private final Type elemType;

		/**
		 * Initializes ArrayToArrayMapper.
		 * 
		 * @param defaultType
		 */
		private ArrayToListMapper(Type targetType) {
			super(ArrayList.class);
			this.elemType = targetType instanceof Class ? Object.class
				: ((ParameterizedType) targetType).getActualTypeArguments()[0];
		}

		/*
		 * (non-Javadoc)
		 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
		 */
		@SuppressWarnings("unchecked")
		@Override
		public List mapTo(IArrayNode from, List target) {
			final int targetSize = from.size();
			CollectionUtil.ensureSize(target, targetSize);
			if (this.elemType == Object.class)
				for (int index = 0; index < targetSize; index++)
					target.set(index, INSTANCE.map(from.get(index), target.get(index)));
			else
				for (int index = 0; index < targetSize; index++)
					target.set(index, INSTANCE.map(from.get(index), target.get(index), this.elemType));

			target.subList(targetSize, target.size()).clear();
			return target;
		}
	}

	private static class EnumMapper extends TypeMapper<TextNode, Enum<?>> {
		private final Map<TextNode, Enum<?>> values = new HashMap<TextNode, Enum<?>>();

		private final Type targetType;

		/**
		 * Initializes ArrayToArrayMapper.
		 * 
		 * @param defaultType
		 */
		private EnumMapper(Type targetType) {
			super(null);
			this.targetType = targetType;
			@SuppressWarnings("unchecked")
			final Enum<?>[] enumConstants = ((Class<? extends Enum<?>>) targetType).getEnumConstants();
			for (Enum<?> constant : enumConstants)
				this.values.put(TextNode.valueOf(constant.name()), constant);
		}

		/*
		 * (non-Javadoc)
		 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
		 */
		@Override
		public Enum<?> mapTo(TextNode from, Enum<?> target) {
			final Enum<?> value = this.values.get(from);
			if (value == null)
				throw new IllegalArgumentException(String.format("Unknown enum value %s for enum %s", from,
					this.targetType));
			return value;
		}
	};

	/**
	 * @author arv
	 */
	private static final TypeMapper<INumericNode, Number> DefaultNumberMapper = new TypeMapper<INumericNode, Number>(null) {
		/*
		 * (non-Javadoc)
		 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
		 */
		@Override
		public Number mapTo(INumericNode from, Number target) {
			return from.getJavaValue();
		}
	};

	/**
	 * The default instance.
	 */
	public static final JsonToJavaMapper INSTANCE = new JsonToJavaMapper();

	protected JsonToJavaMapper() {
		addDefaultTypeMapping(IntNode.class, Integer.class);
		addDefaultTypeMapping(LongNode.class, Long.class);
		addDefaultTypeMapping(BigIntegerNode.class, BigInteger.class);
		addDefaultTypeMapping(DecimalNode.class, BigDecimal.class);
		addDefaultTypeMapping(DoubleNode.class, Double.class);
		addDefaultTypeMapping(TextNode.class, String.class);
		addDefaultTypeMapping(BooleanNode.class, Boolean.class);
		addDefaultTypeMapping(IObjectNode.class, Map.class);
		addDefaultTypeMapping(IArrayNode.class, List.class);

		addMissingAndNullMappers();
		addBooleanMappers();
		this.addStringMappers();
		this.addIntegerMappers();
		this.addLongMappers();
		this.addDoubleMappers();
		addMapper(DecimalNode.class, BigDecimal.class, DefaultNumberMapper);
		addMapper(BigIntegerNode.class, BigInteger.class, DefaultNumberMapper);
		addGeneralMappers();
	}

	private void addMissingAndNullMappers() {
		final TypeMapper<IJsonNode, Object> mapper = new TypeMapper<IJsonNode, Object>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public Object mapTo(IJsonNode from, Object target) {
				return null;
			}
		};
		addMapper(MissingNode.class, Object.class, mapper);
		addMapper(NullNode.class, Object.class, mapper);
	}

	private void addBooleanMappers() {
		TypeMapper<BooleanNode, Boolean> toBooleanMapper = new TypeMapper<BooleanNode, Boolean>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public Boolean mapTo(BooleanNode from, Boolean target) {
				return Boolean.valueOf(from.getBooleanValue());
			}
		};
		addMapper(BooleanNode.class, Boolean.class, toBooleanMapper);
		addMapper(BooleanNode.class, Boolean.TYPE, toBooleanMapper);
	}

	private void addGeneralMappers() {
		final TypeMapper<IJsonNode, String> toStringMapper = new TypeMapper<IJsonNode, String>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public String mapTo(IJsonNode from, String target) {
				return from.toString();
			}
		};
		addMapper(IJsonNode.class, String.class, toStringMapper);
		addMapper(IJsonNode.class, CharSequence.class, toStringMapper);
		addMapper(IJsonNode.class, StringBuilder.class, new TypeMapper<IJsonNode, StringBuilder>(StringBuilder.class) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public StringBuilder mapTo(IJsonNode from, StringBuilder target) {
				target.setLength(0);
				try {
					from.appendAsString(target);
				} catch (IOException e) {
				}
				return target;
			}
		});
	}

	private void addStringMappers() {
		final TypeMapper<TextNode, String> toStringMapper = new TypeMapper<TextNode, String>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public String mapTo(TextNode from, String target) {
				return from.toString();
			}
		};
		addMapper(TextNode.class, String.class, toStringMapper);
		addMapper(TextNode.class, CharSequence.class, toStringMapper);
		addMapper(TextNode.class, StringBuilder.class, new TypeMapper<TextNode, StringBuilder>(StringBuilder.class) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public StringBuilder mapTo(TextNode from, StringBuilder target) {
				target.setLength(0);
				target.append(from);
				return target;
			}
		});
		addMapper(TextNode.class, StringBuffer.class, new TypeMapper<TextNode, StringBuffer>(StringBuffer.class) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public StringBuffer mapTo(TextNode from, StringBuffer target) {
				target.setLength(0);
				target.append(from);
				return target;
			}
		});
		addMapper(TextNode.class, char[].class, new TypeMapper<TextNode, char[]>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public char[] mapTo(TextNode from, char[] target) {
				return from.toArray();
			}
		});
	}

	private void addLongMappers() {
		addMapper(LongNode.class, Long.class, DefaultNumberMapper);
		addMapper(LongNode.class, Long.TYPE, DefaultNumberMapper);
	}

	private void addDoubleMappers() {
		addMapper(DoubleNode.class, Double.class, DefaultNumberMapper);
		addMapper(DoubleNode.class, Double.TYPE, DefaultNumberMapper);
		final TypeMapper<INumericNode, Float> fromFloat = new TypeMapper<INumericNode, Float>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public Float mapTo(INumericNode from, Float target) {
				return Float.valueOf((float) from.getDoubleValue());
			}
		};
		addMapper(DoubleNode.class, Float.class, fromFloat);
		addMapper(DoubleNode.class, Float.TYPE, fromFloat);
	}

	private void addIntegerMappers() {
		addMapper(IntNode.class, Integer.class, DefaultNumberMapper);
		addMapper(IntNode.class, Integer.TYPE, DefaultNumberMapper);
		final TypeMapper<INumericNode, Byte> toByte = new TypeMapper<INumericNode, Byte>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public Byte mapTo(INumericNode from, Byte target) {
				return Byte.valueOf((byte) from.getIntValue());
			}
		};
		addMapper(IntNode.class, Byte.class, toByte);
		addMapper(IntNode.class, Byte.TYPE, toByte);
		final TypeMapper<INumericNode, Short> toShort = new TypeMapper<INumericNode, Short>(null) {
			/*
			 * (non-Javadoc)
			 * @see eu.stratosphere.sopremo.type.TypeMapper.Mapper#map(java.lang.Object, java.lang.Object)
			 */
			@Override
			public Short mapTo(INumericNode from, Short target) {
				return Short.valueOf((short) from.getIntValue());
			}
		};
		addMapper(IntNode.class, Short.class, toShort);
		addMapper(IntNode.class, Short.TYPE, toShort);
	}

	public <T> T map(IJsonNode from, T to, Type targetType) {
		@SuppressWarnings("unchecked")
		TypeMapper<IJsonNode, T> targetMapper = (TypeMapper<IJsonNode, T>) getMapper(from.getClass(), targetType);
		if (targetMapper == null)
			throw new IllegalArgumentException(String.format("Cannot map %s to %s", from, targetType));

		if (to == null && targetMapper.getDefaultType() != null)
			to = ReflectUtil.newInstance(targetMapper.getDefaultType());

		return targetMapper.mapTo(from, to);
	}

	@SuppressWarnings("unchecked")
	public <F extends IJsonNode, T> TypeMapper<F, T> getMapper(Class<F> fromClass, Class<T> targetType) {
		return (TypeMapper<F, T>) super.getMapper(fromClass, targetType);
	}

	public <T> T map(IJsonNode from, T to, Class<T> targetType) {
		return map(from, to, (Type) targetType);
	}

	@Override
	protected Type findDefaultMappingType(final Class<?> fromClass) {
		if (fromClass.isArray()) {
			return IArrayNode.class;
		} else if (ITypedObjectNode.class.isAssignableFrom(fromClass)) {
			return fromClass;
		}

		return super.findDefaultMappingType(fromClass);
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.sopremo.type.AbstractTypeMapper#findMapper(java.lang.Class, java.lang.Class,
	 * java.lang.reflect.Type, java.lang.Class)
	 */
	@Override
	protected TypeMapper<?, ?> findMapper(Class<?> fromClass, Class<?> originalFromClass, Type targetType,
			Class<?> rawTarget) {
		final TypeMapper<?, ?> mapper;
		if (rawTarget.isArray())
			addMapper(fromClass, rawTarget, mapper = new ArrayToArrayMapper(targetType));
		else if (Collection.class.isAssignableFrom(rawTarget))
			addMapper(fromClass, rawTarget, mapper = new ArrayToListMapper(targetType));
		else if (rawTarget.isEnum())
			addMapper(fromClass, rawTarget, mapper = new EnumMapper(targetType));
		else if (fromClass == rawTarget)
			addMapper(fromClass, fromClass, mapper = JavaToJsonMapper.SelfMapper);
		else if (Map.class.isAssignableFrom(rawTarget))
			addMapper(fromClass, rawTarget, mapper = new ObjectToMapMapper(targetType));
		else
			mapper = super.findMapper(fromClass, originalFromClass, targetType, rawTarget);
		return mapper;
	}

	public <T> T map(IJsonNode from, T to) {
		return map(from, to, getDefaultMappingType(from.getClass()));
	}

	public <T> T map(IJsonNode from) {
		return map(from, null, getDefaultMappingType(from.getClass()));
	}
}
