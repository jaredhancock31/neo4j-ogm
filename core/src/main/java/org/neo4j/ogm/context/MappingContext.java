/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and license terms. Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package org.neo4j.ogm.context;

import java.util.*;
import java.util.stream.Collectors;

import org.neo4j.ogm.metadata.ClassInfo;
import org.neo4j.ogm.metadata.FieldInfo;
import org.neo4j.ogm.metadata.MetaData;
import org.neo4j.ogm.utils.EntityUtils;

/**
 * The MappingContext maintains a map of all the objects created during the hydration
 * of an object map (domain hierarchy). The MappingContext lifetime is concurrent
 * with a session lifetime.
 *
 * @author Vince Bickers
 * @author Luanne Misquitta
 * @author Mark Angrish
 */
public class MappingContext {

    // map Neo4j id -> entity
    private final Map<Long, Object> nodeEntityRegister;

    // map primary index value -> entity
    private final Map<Object, Object> primaryIndexNodeRegister;

    private final Map<Long, Object> relationshipEntityRegister;

    private final Set<MappedRelationship> relationshipRegister;

    private final IdentityMap identityMap;

    private final MetaData metaData;


    public MappingContext(MetaData metaData) {
        this.metaData = metaData;
        this.identityMap = new IdentityMap(metaData);
        this.nodeEntityRegister = new HashMap<>();
        this.primaryIndexNodeRegister = new HashMap<>();
        this.relationshipEntityRegister = new HashMap<>();
        this.relationshipRegister = new HashSet<>();
    }

    /**
     * Gets an entity from the MappingContext.
     * @param id The id to look for. Can be either the native db long id (@GraphId) or a user defined id (@Id)
     * @return The entity or null if not found.
     */
    public Object getNodeEntity(Object id) {

        Object result = null;

        if (id instanceof Long) {
            result = nodeEntityRegister.get(id);
        }

        if (result == null) {
            result = primaryIndexNodeRegister.get(id);
        }

        return result;
    }

    /**
     * Adds an entity to the MappingContext.
     * @param entity The object to add.
     * @return The object added, never null.
     */
    public Object addNodeEntity(Object entity) {

        ClassInfo classInfo = metaData.classInfo(entity);
        Long id = EntityUtils.getEntityId(metaData, entity);

        if (nodeEntityRegister.putIfAbsent(id, entity) == null) {
            remember(entity);
            final FieldInfo primaryIndexField = classInfo.primaryIndexField(); // also need to add the class to key to prevent collisions.
            if (primaryIndexField != null) {
                final Object primaryIndexValue = primaryIndexField.read(entity);
                primaryIndexNodeRegister.putIfAbsent(primaryIndexValue, entity);
            }
        }

        return entity;
    }

    boolean removeRelationship(MappedRelationship mappedRelationship) {
        return relationshipRegister.remove(mappedRelationship);
    }

    /**
     * De-registers an object from the mapping context
     * - removes the object instance from the typeRegister(s)
     * - removes the object id from the nodeEntityRegister
     * - removes any relationship entities from relationshipEntityRegister if they have this object either as start or end node
     *
     * @param entity the object to deregister
     */
    void removeNodeEntity(Object entity, boolean deregisterDependentRelationshipEntity) {

        Long id = EntityUtils.getEntityId(metaData, entity);

        nodeEntityRegister.remove(id);
        final ClassInfo primaryIndexClassInfo = metaData.classInfo(entity);
        final FieldInfo primaryIndexField = primaryIndexClassInfo.primaryIndexField(); // also need to add the class to key to prevent collisions.
        if (primaryIndexField != null) {
            final Object primaryIndexValue = primaryIndexField.read(entity);
            primaryIndexNodeRegister.remove(primaryIndexValue);
        }

        if (deregisterDependentRelationshipEntity) {
        deregisterDependentRelationshipEntity(entity);
    }
    }

    public void replaceNodeEntity(Object entity) {

        removeNodeEntity(entity, false);
        addNodeEntity(entity);
    }

    public void replaceRelationshipEntity(Object entity, Long id) {
        relationshipEntityRegister.remove(id);
        addRelationshipEntity(entity, id);
    }

    /**
     * Get a collection of entities or relationships registered in the current context.
     * @param type The base type to search for.
     * @return The entities found. Note that the collection will contain the concrete type given as a parameter,
     * but also all entities that are assignable to it (sub types)
     */
    Collection<Object> getEntities(Class<?> type) {
        Collection<Object> result;
        if (metaData.isRelationshipEntity(type.getName())) {
            result = relationshipEntityRegister.values().stream()
                    .filter((c) -> c.getClass().isAssignableFrom(type))
                    .collect(Collectors.toList());
        } else {
            result = nodeEntityRegister.values().stream()
                    .filter((c) -> c.getClass().isAssignableFrom(type))
                    .collect(Collectors.toList());
        }
        return result;
    }

    /**
     * Return dynamic label information about the entity (@Labels). History contains a snapshot of labels the entity had
     * when registered in the context, and the current labels.
     * @param entity The entity to inspect
     * @return The label information
     */
    LabelHistory labelHistory(Object entity) {
        return identityMap.labelHistory(entity);
    }

    /**
     * Check if the entity has been modified by comparing its current state to the state it had when registered.
     * TBD : describe how the hash is computed. Not sure if it is a real deep hash.
     * @param entity The entity to check
     * @return true if the entity was changed, false otherwise.
     */
    public boolean isDirty(Object entity) {
        return !identityMap.remembered(entity);
    }

    public boolean containsRelationship(MappedRelationship relationship) {
        return relationshipRegister.contains(relationship);
    }

    public Set<MappedRelationship> getRelationships() {
        return relationshipRegister;
    }

    public void addRelationship(MappedRelationship relationship) {
        if (relationship.getRelationshipId() != null && relationshipEntityRegister.get(relationship.getRelationshipId()) == null) {
            relationship.setRelationshipId(null); //We're only interested in id's of relationship entities
        }
        relationshipRegister.add(relationship);
    }

    public void clear() {
        identityMap.clear();
        relationshipRegister.clear();
        nodeEntityRegister.clear();
        primaryIndexNodeRegister.clear();
        relationshipEntityRegister.clear();
    }

    public Object getRelationshipEntity(Long relationshipId) {
        return relationshipEntityRegister.get(relationshipId);
    }

    public Object addRelationshipEntity(Object relationshipEntity, Long id) {
        if (relationshipEntityRegister.putIfAbsent(id, relationshipEntity) == null) {
            relationshipEntity = relationshipEntityRegister.get(id);
            remember(relationshipEntity);
        }
        return relationshipEntity;
    }

    /**
     * purges all information about objects of the supplied type
     * from the mapping context. If the type is an interface, purges all implementing classes
     * in the interface hierarchy
     *
     * @param type the type whose object references and relationship mappings we want to purge
     */
    public void removeType(Class<?> type) {

        ClassInfo classInfo = metaData.classInfo(type.getName());

        if (classInfo.isInterface()) {
            List<ClassInfo> implementingClasses = metaData.getImplementingClassInfos(classInfo.name());
            for (ClassInfo implementingClass : implementingClasses) {
                try {
                    removeType(classInfo.getUnderlyingClass());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        } else {
            for (Object entity : getEntities(type)) {
				purge(entity, type);
			}
        }
    }


    /*
     * purges all information about a node entity with this id
     */
    public boolean detachNodeEntity(Object id) {
        Object objectToDetach = getNodeEntity(id);
        if (objectToDetach != null) {
            removeEntity(objectToDetach);
            return true;
        }
        return false;
    }

    /*
     * purges all information about a relationship entity with this id
     */
    public boolean detachRelationshipEntity(Long id) {
        Object objectToDetach = relationshipEntityRegister.get(id);
        if (objectToDetach != null) {
            removeEntity(objectToDetach);
            return true;
        }
        return false;
    }

    /**
     * removes all information about this object from the mapping context
     *
     * @param entity the instance whose references and relationship mappings we want to purge
     */
    void removeEntity(Object entity) {
        Class<?> type = entity.getClass();
        purge(entity, type);
    }

    /**
     * purges all information about this object from the mapping context
     * and also sets its id to null. Should be called for new objects that have had their
     * id assigned as part of a long-running transaction, if that transaction is subsequently
     * rolled back.
     *
     * @param entity the instance whose references and relationship mappings we want to reset
     */
    public void reset(Object entity) {
        removeEntity(entity);
        EntityUtils.setIdentityId(metaData, entity, null);
    }


    /**
     * Get related objects of an entity / relationship. Used in deletion scenarios.
     * @param entity The entity to look neighbours for.
     * @return If entity is a relationship, end and start nodes. If entity is a node, the relations pointing to it.
     */
    public Set<Object> neighbours(Object entity) {

        Set<Object> neighbours = new HashSet<>();

        Class<?> type = entity.getClass();
        ClassInfo classInfo = metaData.classInfo(type.getName());

        Long id = EntityUtils.getEntityId(metaData, entity);

        if (id != null) {
            if (!metaData.isRelationshipEntity(type.getName())) {
                if (getNodeEntity(id) != null) {
                    // todo: this will be very slow for many objects
                    // todo: refactor to create a list of mappedRelationships from a nodeEntity id.
                    for (MappedRelationship mappedRelationship : relationshipRegister) {
                        if (mappedRelationship.getStartNodeId() == id || mappedRelationship.getEndNodeId() == id) {
                            Object affectedObject = mappedRelationship.getEndNodeId() == id ? getNodeEntity(mappedRelationship.getStartNodeId()) : getNodeEntity(mappedRelationship.getEndNodeId());
                            if (affectedObject != null) {
                                neighbours.add(affectedObject);
                            }
                        }
                    }
                }
            } else if (relationshipEntityRegister.containsKey(id)) {
                FieldInfo startNodeReader = classInfo.getStartNodeReader();
                FieldInfo endNodeReader = classInfo.getEndNodeReader();
                neighbours.add(startNodeReader.read(entity));
                neighbours.add(endNodeReader.read(entity));
            }
        }

        return neighbours;
    }

    /**
     * Deregister a relationship entity if it has either start or end node equal to the supplied startOrEndEntity
     *
     * @param startOrEndEntity the entity that might be the start or end node of a relationship entity
     */
    private void deregisterDependentRelationshipEntity(Object startOrEndEntity) {
        Iterator<Long> relationshipEntityIdIterator = relationshipEntityRegister.keySet().iterator();
        while (relationshipEntityIdIterator.hasNext()) {
            Long relationshipEntityId = relationshipEntityIdIterator.next();
            Object relationshipEntity = relationshipEntityRegister.get(relationshipEntityId);
            final ClassInfo classInfo = metaData.classInfo(relationshipEntity);
            FieldInfo startNodeReader = classInfo.getStartNodeReader();
            FieldInfo endNodeReader = classInfo.getEndNodeReader();
            if (startOrEndEntity == startNodeReader.read(relationshipEntity) || startOrEndEntity == endNodeReader.read(relationshipEntity)) {
                relationshipEntityIdIterator.remove();
            }
        }
    }

    private void purge(Object entity, Class type) {
        Long id = EntityUtils.getEntityId(metaData, entity);
        Set<Object> relEntitiesToPurge = new HashSet<>();
        if (id != null) {
            // remove a NodeEntity
            if (!metaData.isRelationshipEntity(type.getName())) {
                if (getNodeEntity(id) != null) {
                    // remove the object from the node register
                    removeNodeEntity(entity, false);
                    // remove all relationship mappings to/from this object
                    Iterator<MappedRelationship> mappedRelationshipIterator = relationshipRegister.iterator();
                    while (mappedRelationshipIterator.hasNext()) {
                        MappedRelationship mappedRelationship = mappedRelationshipIterator.next();
                        if (mappedRelationship.getStartNodeId() == id || mappedRelationship.getEndNodeId() == id) {

                            // first purge any RE mappings (if its a RE)
                            if (mappedRelationship.getRelationshipId() != null) {
                                Object relEntity = relationshipEntityRegister.get(mappedRelationship.getRelationshipId());
                                if (relEntity != null) {
                                    // TODO : extract the "remove a RelationshipEntity" block below in a method
                                    // and call it here instead of going recursive ?
                                    relEntitiesToPurge.add(relEntity);
                                }
                            }
                            // finally remove the mapped relationship
                            mappedRelationshipIterator.remove();
                        }
                    }
                }
            } else {
                // remove a RelationshipEntity
                if (relationshipEntityRegister.containsKey(id)) {
                    relationshipEntityRegister.remove(id);
                    final ClassInfo classInfo = metaData.classInfo(entity);
                    FieldInfo startNodeReader = classInfo.getStartNodeReader();
                    Object startNode = startNodeReader.read(entity);
                    removeEntity(startNode);
                    FieldInfo endNodeReader = classInfo.getEndNodeReader();
                    Object endNode = endNodeReader.read(entity);
                    removeEntity(endNode);
                }
            }
            for (Object relEntity : relEntitiesToPurge) {
                ClassInfo relClassInfo = metaData.classInfo(relEntity);
                purge(relEntity, relClassInfo.getUnderlyingClass());
            }
        }
    }

    private void remember(Object entity) {
        identityMap.remember(entity);
    }

}
