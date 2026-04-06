package iped.engine.webapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;

import iped.engine.data.IPEDSource;
import iped.properties.BasicProps;

/**
 * Helper to build Lucene Sort objects from web API field names,
 * and to enumerate all sortable fields from the index.
 */
public class SortFieldHelper {

    /**
     * Known numeric fields and their concrete Lucene SortField.Type.
     * Fields indexed with NumericDocValuesField / SortedNumericDocValuesField
     * need the correct numeric type for Lucene to decode them.
     */
    private static final Map<String, SortField.Type> KNOWN_NUMERIC_TYPES;
    static {
        Map<String, SortField.Type> m = new HashMap<>();
        // BasicProps indexed as int / long
        m.put(BasicProps.ID, SortField.Type.LONG);
        m.put(BasicProps.PARENTID, SortField.Type.LONG);
        m.put(BasicProps.SUBITEMID, SortField.Type.LONG);
        m.put(BasicProps.LENGTH, SortField.Type.LONG);
        KNOWN_NUMERIC_TYPES = Collections.unmodifiableMap(m);
    }

    /**
     * Describes a sortable index field.
     */
    public static class SortableField {
        public final String name;
        public final String type; // "STRING", "LONG", "INT", "DOUBLE", "FLOAT"

        public SortableField(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * Enumerates all sortable fields from the given source's index.
     * A field is sortable if it has DocValues of type SORTED, SORTED_SET,
     * NUMERIC, or SORTED_NUMERIC.
     */
    public static List<SortableField> getSortableFields(IPEDSource source) {
        List<SortableField> result = new ArrayList<>();
        LeafReader reader = source.getLeafReader();
        if (reader == null) {
            return result;
        }
        FieldInfos fieldInfos = reader.getFieldInfos();
        for (FieldInfo fi : fieldInfos) {
            DocValuesType dvType = fi.getDocValuesType();
            String typeName = resolveTypeName(fi.name, dvType);
            if (typeName != null) {
                result.add(new SortableField(fi.name, typeName));
            }
        }
        result.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return result;
    }

    /**
     * Builds a Lucene {@link Sort} for the given field name and direction,
     * inspecting the index to determine the correct SortField.Type.
     *
     * @param source   the IPEDSource (or IPEDMultiSource) whose index is queried
     * @param field    the index field name to sort by, or "relevance" for score-based sorting
     * @param reverse  true for descending order
     * @return a Sort object ready to pass to Lucene's IndexSearcher
     * @throws IllegalArgumentException if the field does not exist or is not sortable
     */
    public static Sort buildSort(IPEDSource source, String field, boolean reverse) {
        if ("relevance".equalsIgnoreCase(field)) {
            // Score-based sorting: highest score first when reverse=false (default desc for score),
            // lowest score first when reverse=true. SortField.FIELD_SCORE is naturally descending.
            return new Sort(new SortField(null, SortField.Type.SCORE, reverse));
        }

        LeafReader reader = source.getLeafReader();
        if (reader == null) {
            throw new IllegalArgumentException("Index is not available");
        }
        FieldInfos fieldInfos = reader.getFieldInfos();
        FieldInfo fi = fieldInfos.fieldInfo(field);
        if (fi == null) {
            throw new IllegalArgumentException("Unknown field: " + field);
        }

        DocValuesType dvType = fi.getDocValuesType();
        switch (dvType) {
            case SORTED:
            case SORTED_SET:
                // String-valued or date-valued (ISO-8601 strings sort lexicographically = chronologically)
                if (dvType == DocValuesType.SORTED_SET) {
                    // SortedSetDocValues needs SortedSetSortField
                    return new Sort(new org.apache.lucene.search.SortedSetSortField(field, reverse));
                }
                return new Sort(new SortField(field, SortField.Type.STRING, reverse));

            case NUMERIC: {
                SortField.Type sfType = resolveNumericSortFieldType(field);
                return new Sort(new SortField(field, sfType, reverse));
            }

            case SORTED_NUMERIC: {
                SortField.Type sfType = resolveNumericSortFieldType(field);
                return new Sort(new SortedNumericSortField(field, sfType, reverse));
            }

            default:
                throw new IllegalArgumentException(
                        "Field '" + field + "' is not sortable (DocValuesType=" + dvType + ")");
        }
    }

    /**
     * Resolves the Lucene SortField.Type for a numeric field.
     * Uses the KNOWN_NUMERIC_TYPES map for basic properties, otherwise
     * tries MetadataUtil's type map, and defaults to LONG.
     */
    private static SortField.Type resolveNumericSortFieldType(String fieldName) {
        SortField.Type known = KNOWN_NUMERIC_TYPES.get(fieldName);
        if (known != null) {
            return known;
        }

        // Check MetadataUtil's type map
        Map<String, Class<?>> metaTypes = iped.parsers.util.MetadataUtil.getMetadataTypes();
        Class<?> clazz = metaTypes.get(fieldName);
        if (clazz != null) {
            if (clazz == Float.class || clazz == float.class) {
                return SortField.Type.FLOAT;
            } else if (clazz == Double.class || clazz == double.class) {
                return SortField.Type.DOUBLE;
            } else if (clazz == Integer.class || clazz == int.class
                    || clazz == Short.class || clazz == short.class
                    || clazz == Byte.class || clazz == byte.class) {
                return SortField.Type.LONG; // stored as NumericDocValuesField(long) even for int
            }
        }

        // Default to LONG — the most common numeric DocValues type in Lucene
        return SortField.Type.LONG;
    }

    /**
     * Returns a human-readable type name for a field, or null if not sortable.
     */
    private static String resolveTypeName(String fieldName, DocValuesType dvType) {
        switch (dvType) {
            case SORTED:
            case SORTED_SET:
                return "STRING";
            case NUMERIC:
            case SORTED_NUMERIC:
                return resolveNumericSortFieldType(fieldName).name();
            default:
                return null;
        }
    }
}
