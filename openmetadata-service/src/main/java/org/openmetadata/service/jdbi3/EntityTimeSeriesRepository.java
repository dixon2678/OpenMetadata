package org.openmetadata.service.jdbi3;

import static org.openmetadata.schema.type.Include.ALL;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import org.jdbi.v3.sqlobject.transaction.Transaction;
import org.openmetadata.schema.EntityTimeSeriesInterface;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.service.Entity;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.util.JsonUtils;
import org.openmetadata.service.util.RestUtil;
import org.openmetadata.service.util.ResultList;

@Repository
public abstract class EntityTimeSeriesRepository<T extends EntityTimeSeriesInterface> {
  @Getter protected final String collectionPath;
  @Getter protected final EntityTimeSeriesDAO timeSeriesDao;
  @Getter protected final SearchRepository searchRepository;
  @Getter protected final String entityType;
  @Getter protected final Class<T> entityClass;
  @Getter protected final CollectionDAO daoCollection;

  protected EntityTimeSeriesRepository(
      String collectionPath,
      EntityTimeSeriesDAO timeSeriesDao,
      Class<T> entityClass,
      String entityType) {
    this.collectionPath = collectionPath;
    this.timeSeriesDao = timeSeriesDao;
    this.entityClass = entityClass;
    this.entityType = entityType;
    this.searchRepository = Entity.getSearchRepository();
    this.daoCollection = Entity.getCollectionDAO();
    Entity.registerEntity(entityClass, entityType, this);
  }

  @Transaction
  public T createNewRecord(T recordEntity, String extension, String recordFQN) {
    recordEntity.setId(UUID.randomUUID());
    storeInternal(recordEntity, recordFQN, extension);
    storeRelationshipInternal(recordEntity);
    postCreate(recordEntity);
    return recordEntity;
  }

  public T createNewRecord(T recordEntity, String recordFQN) {
    recordEntity.setId(UUID.randomUUID());
    storeInternal(recordEntity, recordFQN);
    storeRelationshipInternal(recordEntity);
    postCreate(recordEntity);
    return recordEntity;
  }

  @Transaction
  protected void storeInternal(T recordEntity, String recordFQN) {
    timeSeriesDao.insert(recordFQN, entityType, JsonUtils.pojoToJson(recordEntity));
  }

  @Transaction
  protected void storeInternal(T recordEntity, String recordFQN, String extension) {
    timeSeriesDao.insert(recordFQN, extension, entityType, JsonUtils.pojoToJson(recordEntity));
  }

  protected void storeRelationshipInternal(T recordEntity) {
    storeRelationship(recordEntity);
  }

  protected void storeRelationship(T recordEntity) {
    // Nothing to do in the default implementation
  }

  protected void setInheritedFields(T recordEntity) {
    // Nothing to do in the default implementation
  }

  @Transaction
  public final void addRelationship(
      UUID fromId,
      UUID toId,
      String fromEntity,
      String toEntity,
      Relationship relationship,
      String json,
      boolean bidirectional) {
    UUID from = fromId;
    UUID to = toId;
    if (bidirectional && fromId.compareTo(toId) > 0) {
      // For bidirectional relationship, instead of adding two row fromId -> toId and toId ->
      // fromId, just add one row where fromId is alphabetically less than toId
      from = toId;
      to = fromId;
    }
    daoCollection
        .relationshipDAO()
        .insert(from, to, fromEntity, toEntity, relationship.ordinal(), json);
  }

  protected void postCreate(T recordEntity) {
    searchRepository.createTimeSeriesEntity(JsonUtils.deepCopy(recordEntity, entityClass));
  }

  protected void postDelete(T recordEntity) {
    searchRepository.deleteTimeSeriesEntityById(JsonUtils.deepCopy(recordEntity, entityClass));
  }

  public final List<CollectionDAO.EntityRelationshipRecord> findFromRecords(
      UUID toId, String toEntityType, Relationship relationship, String fromEntityType) {
    // When fromEntityType is null, all the relationships from any entity is returned
    return fromEntityType == null
        ? daoCollection.relationshipDAO().findFrom(toId, toEntityType, relationship.ordinal())
        : daoCollection
            .relationshipDAO()
            .findFrom(toId, toEntityType, relationship.ordinal(), fromEntityType);
  }

  protected EntityReference getFromEntityRef(
      UUID toId, Relationship relationship, String fromEntityType, boolean mustHaveRelationship) {
    List<CollectionDAO.EntityRelationshipRecord> records =
        findFromRecords(toId, entityType, relationship, fromEntityType);
    EntityRepository.ensureSingleRelationship(
        entityType, toId, records, relationship.value(), fromEntityType, mustHaveRelationship);
    return !records.isEmpty()
        ? Entity.getEntityReferenceById(records.get(0).getType(), records.get(0).getId(), ALL)
        : null;
  }

  public final ResultList<T> getResultList(
      List<T> entities, String beforeCursor, String afterCursor, int total) {
    return new ResultList<>(entities, beforeCursor, afterCursor, total);
  }

  /**
   * Forward paginate a list of entities ordered and paginated by timestamp
   *
   * @return ResultList
   */
  protected ResultList<T> listWithOffset(
      String offset, ListFilter filter, int limitParam, Long startTs, Long endTs, boolean latest) {
    int total = timeSeriesDao.listCount(filter, startTs, endTs, latest);
    List<T> entityList = new ArrayList<>();

    int offsetInt = offset != null ? Integer.parseInt(RestUtil.decodeCursor(offset)) : 0;
    int afterOffsetInt = offsetInt + limitParam;
    int beforeOffsetInt = offsetInt - limitParam;

    // If offset is negative, then set it to 0 if you pass offset 4 and limit 10, then the previous
    // page will be at offset 0
    if (beforeOffsetInt < 0) beforeOffsetInt = 0;

    // if offsetInt is 0 (i.e. either no offset or offset is 0), then set it to null as there is no
    // previous page
    String beforeOffset = (offsetInt == 0) ? null : String.valueOf(beforeOffsetInt);

    // If afterOffset is greater than total, then set it to null to indicate end of list
    String afterOffset = afterOffsetInt >= total ? null : String.valueOf(afterOffsetInt);

    if (limitParam > 0) {
      List<String> jsons =
          timeSeriesDao.listWithOffset(filter, limitParam, offsetInt, startTs, endTs, latest);

      for (String json : jsons) {
        T recordEntity = JsonUtils.readValue(json, entityClass);
        setInheritedFields(recordEntity);
        entityList.add(recordEntity);
      }
      return getResultList(entityList, beforeOffset, afterOffset, total);
    } else {
      return getResultList(entityList, null, null, total);
    }
  }

  public ResultList<T> list(
      String offset, Long startTs, Long endTs, int limitParam, ListFilter filter, boolean latest) {
    return listWithOffset(offset, filter, limitParam, startTs, endTs, latest);
  }

  public T getLatestRecord(String recordFQN) {
    String jsonRecord = timeSeriesDao.getLatestRecord(recordFQN);
    if (jsonRecord == null) {
      return null;
    }
    T entityRecord = JsonUtils.readValue(jsonRecord, entityClass);
    setInheritedFields(entityRecord);
    return entityRecord;
  }

  public T getById(UUID id) {
    String jsonRecord = timeSeriesDao.getById(id);
    if (jsonRecord == null) {
      return null;
    }
    T entityRecord = JsonUtils.readValue(jsonRecord, entityClass);
    setInheritedFields(entityRecord);
    return entityRecord;
  }

  public void deleteById(UUID id, boolean hardDelete) {
    if (!hardDelete) {
      // time series entities by definition cannot be soft deleted (i.e. they do not have a state
      // and they should be immutable) thought they can be contained inside entities that can be
      // soft deleted
      return;
    }
    T entityRecord = getById(id);
    if (entityRecord == null) {
      return;
    }
    timeSeriesDao.deleteById(id);
  }
}
