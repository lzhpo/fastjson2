package com.alibaba.fastjson2.reader;

import com.alibaba.fastjson2.JSONB;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.schema.JSONSchema;
import com.alibaba.fastjson2.util.UnsafeUtils;

import java.lang.reflect.Type;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.alibaba.fastjson2.JSONB.Constants.BC_OBJECT;
import static com.alibaba.fastjson2.JSONB.Constants.BC_OBJECT_END;
import static com.alibaba.fastjson2.JSONReader.Feature.SupportArrayToBean;
import static com.alibaba.fastjson2.util.JDKUtils.UNSAFE_SUPPORT;

public class ObjectReader2<T>
        extends ObjectReaderBean<T> {
    final long features;
    final Supplier<T> defaultCreator;
    final Function buildFunction;
    private final FieldReader first;
    private final FieldReader second;
    private final long firstHashCode;
    private final long secondHashCode;
    private final long firstHashCodeLCase;
    private final long secondHashCodeLCase;

    public ObjectReader2(
            Class objectClass,
            long features,
            JSONSchema schema,
            Supplier<T> defaultCreator,
            Function buildFunction,
            FieldReader first,
            FieldReader second) {
        super(objectClass, null, schema);

        this.features = features;
        this.defaultCreator = defaultCreator;
        this.buildFunction = buildFunction;

        this.first = first;
        this.second = second;

        this.firstHashCode = first.fieldNameHash;
        this.firstHashCodeLCase = first.fieldNameHashLCase;

        this.secondHashCode = second.fieldNameHash;
        this.secondHashCodeLCase = second.fieldNameHashLCase;

        if (first.isUnwrapped()) {
            extraFieldReader = first;
        }
        if (second.isUnwrapped()) {
            extraFieldReader = second;
        }

        hasDefaultValue = first.defaultValue != null || second.defaultValue != null;
    }

    @Override
    protected void initDefaultValue(T object) {
        first.acceptDefaultValue(object);
        second.acceptDefaultValue(object);
    }

    @Override
    public long getFeatures() {
        return features;
    }

    @Override
    public Function getBuildFunction() {
        return buildFunction;
    }

    @Override
    public T createInstance(long features) {
        return defaultCreator.get();
    }

    @Override
    public T readArrayMappingJSONBObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        if (!serializable) {
            jsonReader.errorOnNoneSerializable(objectClass);
        }

        ObjectReader autoTypeReader = checkAutoType(jsonReader, this.objectClass, this.features | features);
        if (autoTypeReader != null && autoTypeReader != this && autoTypeReader.getObjectClass() != this.objectClass) {
            return (T) autoTypeReader.readArrayMappingJSONBObject(jsonReader, fieldType, fieldName, features);
        }

        Object object = defaultCreator.get();

        int entryCnt = jsonReader.startArray();
        if (entryCnt > 0) {
            first.readFieldValue(jsonReader, object);
            if (entryCnt > 1) {
                second.readFieldValue(jsonReader, object);
                for (int i = 2; i < entryCnt; ++i) {
                    jsonReader.skipValue();
                }
            }
        }

        if (buildFunction != null) {
            return (T) buildFunction.apply(object);
        }

        return (T) object;
    }

    @Override
    public T readJSONBObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        if (!serializable) {
            jsonReader.errorOnNoneSerializable(objectClass);
        }

        ObjectReader autoTypeReader = jsonReader.checkAutoType(this.objectClass, this.typeNameHash, this.features | features);
        if (autoTypeReader != null && autoTypeReader.getObjectClass() != this.objectClass) {
            return (T) autoTypeReader.readJSONBObject(jsonReader, fieldType, fieldName, features);
        }

        if (jsonReader.isArray()) {
            T object = defaultCreator.get();
            if (hasDefaultValue) {
                initDefaultValue(object);
            }

            int entryCnt = jsonReader.startArray();
            if (entryCnt > 0) {
                first.readFieldValue(jsonReader, object);
                if (entryCnt > 1) {
                    second.readFieldValue(jsonReader, object);
                    for (int i = 2; i < entryCnt; ++i) {
                        jsonReader.skipValue();
                    }
                }
            }

            if (buildFunction != null) {
                return (T) buildFunction.apply(object);
            }
            return (T) object;
        }

        if (!jsonReader.nextIfMatch(BC_OBJECT)) {
            throw new JSONException(jsonReader.info("expect object, but " + JSONB.typeName(jsonReader.getType())));
        }

        T object;
        if (defaultCreator != null) {
            object = defaultCreator.get();
        } else if (UNSAFE_SUPPORT && ((features | jsonReader.getContext().getFeatures()) & JSONReader.Feature.FieldBased.mask) != 0) {
            try {
                object = (T) UnsafeUtils.UNSAFE.allocateInstance(objectClass);
            } catch (InstantiationException e) {
                throw new JSONException(jsonReader.info("create instance error"), e);
            }
        } else {
            object = null;
        }

        if (object != null && hasDefaultValue) {
            initDefaultValue(object);
        }

        for (; ; ) {
            if (jsonReader.nextIfMatch(BC_OBJECT_END)) {
                break;
            }

            long hashCode = jsonReader.readFieldNameHashCode();
            if (hashCode == 0) {
                continue;
            }

            if (hashCode == firstHashCode) {
                first.readFieldValue(jsonReader, object);
            } else if (hashCode == secondHashCode) {
                second.readFieldValueJSONB(jsonReader, object);
            } else {
                if (jsonReader.isSupportSmartMatch(features | this.features)) {
                    long nameHashCodeLCase = jsonReader.getNameHashCodeLCase();
                    if (nameHashCodeLCase == firstHashCodeLCase) {
                        first.readFieldValueJSONB(jsonReader, object);
                        continue;
                    } else if (nameHashCodeLCase == secondHashCodeLCase) {
                        second.readFieldValueJSONB(jsonReader, object);
                        continue;
                    }
                }

                processExtra(jsonReader, object);
            }
        }

        if (buildFunction != null) {
            object = (T) buildFunction.apply(object);
        }

        if (schema != null) {
            schema.assertValidate(object);
        }

        return object;
    }

    @Override
    public T readObject(JSONReader jsonReader) {
        return readObject(jsonReader, null, null, features);
    }

    @Override
    public T readObject(JSONReader jsonReader, Type fieldType, Object fieldName, long features) {
        if (!serializable) {
            jsonReader.errorOnNoneSerializable(objectClass);
        }

        if (jsonReader.isJSONB()) {
            return readJSONBObject(jsonReader, fieldType, fieldName, features);
        }

        if (jsonReader.nextIfNull()) {
            jsonReader.nextIfMatch(',');
            return null;
        }

        long featuresAll = jsonReader.features(this.features | features);
        T object;
        if (jsonReader.isArray()) {
            if ((featuresAll & SupportArrayToBean.mask) != 0) {
                jsonReader.next();
                object = defaultCreator.get();
                if (hasDefaultValue) {
                    initDefaultValue(object);
                }

                first.readFieldValue(jsonReader, object);
                second.readFieldValue(jsonReader, object);
                if (jsonReader.current() != ']') {
                    throw new JSONException(jsonReader.info("array to bean end error"));
                }
                jsonReader.next();
                return object;
            }

            return processObjectInputSingleItemArray(jsonReader, fieldType, fieldName, featuresAll);
        }

        jsonReader.nextIfMatch('{');
        object = defaultCreator.get();
        if (hasDefaultValue) {
            initDefaultValue(object);
        }

        for (int i = 0; ; ++i) {
            if (jsonReader.nextIfMatch('}')) {
                break;
            }

            long hashCode = jsonReader.readFieldNameHashCode();

            if (i == 0 && hashCode == HASH_TYPE) {
                long typeHash = jsonReader.readTypeHashCode();
                JSONReader.Context context = jsonReader.getContext();
                ObjectReader autoTypeObjectReader = context.getObjectReaderAutoType(typeHash);
                if (autoTypeObjectReader == null) {
                    String typeName = jsonReader.getString();
                    autoTypeObjectReader = context.getObjectReaderAutoType(typeName, objectClass);

                    if (autoTypeObjectReader == null) {
                        continue;
                    }
                }

                if (autoTypeObjectReader != this) {
                    object = (T) autoTypeObjectReader.readObject(jsonReader, fieldType, fieldName, features);
                    break;
                } else {
                    continue;
                }
            }

            if (hashCode == firstHashCode) {
                first.readFieldValue(jsonReader, object);
            } else if (hashCode == secondHashCode) {
                second.readFieldValue(jsonReader, object);
            } else {
                if (jsonReader.isSupportSmartMatch(features | this.features)) {
                    long nameHashCodeLCase = jsonReader.getNameHashCodeLCase();
                    if (nameHashCodeLCase == firstHashCodeLCase) {
                        first.readFieldValue(jsonReader, object);
                        continue;
                    } else if (nameHashCodeLCase == secondHashCodeLCase) {
                        second.readFieldValue(jsonReader, object);
                        continue;
                    }
                }

                processExtra(jsonReader, object);
            }
        }

        jsonReader.nextIfMatch(',');

        if (buildFunction != null) {
            try {
                object = (T) buildFunction.apply(object);
            } catch (IllegalStateException e) {
                throw new JSONException(jsonReader.info("build object error"), e);
            }
        }

        if (schema != null) {
            schema.assertValidate(object);
        }

        return object;
    }

    @Override
    public FieldReader getFieldReader(long hashCode) {
        if (hashCode == firstHashCode) {
            return first;
        }

        if (hashCode == secondHashCode) {
            return second;
        }

        return null;
    }

    @Override
    public FieldReader getFieldReaderLCase(long hashCode) {
        if (hashCode == firstHashCodeLCase) {
            return first;
        }

        if (hashCode == secondHashCodeLCase) {
            return second;
        }

        return null;
    }
}
