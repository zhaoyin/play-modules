package play.modules.search.store;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.search.SortField;
import play.Logger;
import play.data.binding.Binder;
import play.db.jpa.Blob;
import play.db.jpa.JPABase;
import play.db.jpa.Model;
import play.exceptions.UnexpectedException;
import play.modules.search.Indexed;
import play.modules.search.ModelVersioned;
import play.modules.search.Query.SearchException;

import javax.persistence.Id;
import javax.persistence.ManyToOne;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Date;

/**
 * Various utils handling object to index and query result to object conversion
 *
 * @author jfp
 */
public class ConvertionUtils {

    /**
     * Examines a JPABase object and creates the corresponding Lucene Document
     *
     * @param object to examine, expected a JPABase object
     * @return the corresponding Lucene document
     * @throws Exception
     */
    public static Document toDocument(Object object) throws Exception {
        Indexed indexed = object.getClass().getAnnotation(Indexed.class);
        if (indexed == null)
            return null;
        if (!(object instanceof JPABase))
            return null;
        JPABase jpaBase = (JPABase) object;
        Document document = new Document();
        document.add(new Field("_docID", getIdValueFor(jpaBase) + "", Field.Store.YES, Field.Index.NOT_ANALYZED));

        // copy field of all fields
        StringBuffer allValue = new StringBuffer();

        Object currentObject = object;

        while (currentObject != null) {
            // we index all annotated primitive fields
            for (java.lang.reflect.Field field : currentObject.getClass().getFields()) {
                play.modules.search.Field index = field.getAnnotation(play.modules.search.Field.class);
                if (index == null)
                    continue;
                if (field.getType().isArray())
                    continue;
                if (Collection.class.isAssignableFrom(field.getType()))
                    continue;

                // Creation of name/value pair
                String name = field.getName();
                String value = null;
                if (JPABase.class.isAssignableFrom(field.getType()) && !(index.joinField().length() == 0)) {
                    JPABase joinObject = (JPABase) field.get(currentObject);
                    for (java.lang.reflect.Field joinField : joinObject.getClass().getFields()) {
                        if (joinField.getName().equals(index.joinField())) {
                            value = valueOf(joinObject, joinField);
                        }
                    }
                } else {
                    value = valueOf(currentObject, field);
                }
                if (value == null)
                    continue;

                // adding field to the current document
                Logger.debug("indexing field " + name + " with value " + value);
                document.add(new Field(name, value, index.stored() ? Field.Store.YES : Field.Store.NO, index.tokenize() ? Field.Index.ANALYZED : Field.Index.NOT_ANALYZED));

                if (index.tokenize() && index.sortable()) {
                    document.add(new Field(name + "_untokenized", value, index.stored() ? Field.Store.YES : Field.Store.NO, Field.Index.NOT_ANALYZED));
                }
                allValue.append(value).append(' ');
            }

            // if object implement ModelVersionned, then we index in this document its fields
            if (currentObject instanceof ModelVersioned) {
                Method getLastVersion = currentObject.getClass().getMethod("getLastVersion");
                currentObject = getLastVersion.invoke(currentObject);
            } else {
                currentObject = null;
            }
        }

        // adding copy field of all fields to the current document
        document.add(new Field("allfield", allValue.toString(), Field.Store.NO, Field.Index.ANALYZED));
        return document;
    }

    public static String valueOf(Object object, java.lang.reflect.Field field) throws Exception {
        if (field.getType().equals(String.class)) {
            return (String) field.get(object);
        }
        if (field.getType().equals(Blob.class) && field.get(object) != null) {
            return FileExtractor.getText((Blob) field.get(object));
        }
        if (field.getType().equals(Date.class) && field.get(object) != null) {
            return  "" + ((Date) field.get(object)).getTime();
        }

        Object o = field.get(object);
        if (field.isAnnotationPresent(ManyToOne.class) && o instanceof JPABase) {
            return "" + getIdValueFor((JPABase) o);
        }

        return "" + field.get(object);
    }

    public static int getSortType(Class clazz, String field) throws SearchException {
        java.lang.reflect.Field fi = null;
        try {
            fi = clazz.getField(field);
        } catch (Exception e) {
            Logger.debug("The field " + field + " is not found on class " + clazz + " - trying model version");
            try {
                if (clazz.getMethod("getLastVersion") != null) {
                    Method getLastVersion = clazz.getMethod("getLastVersion");
                    fi = getLastVersion.getReturnType().getField("created");
                    Logger.debug("The field " + field + " is part of model versionned");
                }
            } catch (Exception e2) {
                throw new SearchException("The field " + field + " is not found on class " + clazz);
            }
        }

        Class type = fi.getType();
        if (type.equals(long.class) || type.equals(Long.class)) return SortField.LONG;
        if (type.equals(int.class) || type.equals(Integer.class)) return SortField.INT;
        if (type.equals(double.class) || type.equals(Double.class)) return SortField.DOUBLE;
        if (type.equals(float.class) || type.equals(Float.class)) return SortField.FLOAT;
        if (type.equals(short.class) || type.equals(Short.class)) return SortField.SHORT;
        if (type.equals(byte.class) || type.equals(Byte.class)) return SortField.BYTE;
        if (type.equals(Date.class)) return SortField.STRING;
        return SortField.SCORE;
    }

    /**
     * Looks for the type of the id fiels on the JPABase target class and use
     * play's binder to retrieve the corresponding object used to build JPA load
     * query
     *
     * @param clazz      JPABase target class
     * @param indexValue String value of the id, taken from index
     * @return Object id expected to build query
     */
    public static Object getIdValueFromIndex(Class<?> clazz, String indexValue) {
        java.lang.reflect.Field field = getIdField(clazz);
        Class<?> parameter = field.getType();
        try {
            return Binder.directBind(indexValue, parameter);
        } catch (Exception e) {
            throw new UnexpectedException("Could not convert the ID from index to corresponding type", e);
        }
    }

    /**
     * Find a ID field on the JPABase target class
     *
     * @param clazz JPABase target class
     * @return corresponding field
     */
    public static java.lang.reflect.Field getIdField(Class<?> clazz) {
        for (java.lang.reflect.Field field : clazz.getFields()) {
            if (field.getAnnotation(Id.class) != null) {
                return field;
            }
        }
        throw new RuntimeException("Your class " + clazz.getName()
                + " is annotated with javax.persistence.Id but the field Id was not found");
    }

    /**
     * Lookups the id field, being a Long id for Model and an annotated field @Id
     * for JPABase and returns the field value.
     *
     * @param jpaBase is a Play! Framework that supports JPA
     * @return the field value (a Long or a String for UUID)
     */
    public static Object getIdValueFor(JPABase jpaBase) {
        if (jpaBase instanceof Model) {
            return ((Model) jpaBase).id;
        }

        java.lang.reflect.Field field = getIdField(jpaBase.getClass());
        Object val = null;
        try {
            val = field.get(jpaBase);
        } catch (IllegalAccessException e) {
            Logger.error("Unable to read the field value of a field annotated with @Id " + field.getName() + " due to "
                    + e.getMessage(), e);
        }
        return val;
    }

    public static boolean isForcedUntokenized(Class<?> clazz, String fieldName) {
        try {
            java.lang.reflect.Field field = clazz.getField(fieldName);
            play.modules.search.Field index = field.getAnnotation(play.modules.search.Field.class);
            return index.tokenize() && index.sortable();
        } catch (Exception e) {
            Logger.error("%s", e.getCause());
        }
        return false;
    }
}
