package iped.engine.graph;

/**
 * Listener interface for relationship type queries.
 */
public interface RelationshipTypeQueryListener {

    /**
     * Called when a relationship type is found.
     * 
     * @param relationshipType the name of the relationship type
     */
    void relationshipTypeFound(String relationshipType);

}
