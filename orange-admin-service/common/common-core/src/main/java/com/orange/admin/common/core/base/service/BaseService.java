package com.orange.admin.common.core.base.service;

import com.orange.admin.common.core.annotation.*;
import com.orange.admin.common.core.base.dao.BaseDaoMapper;
import com.orange.admin.common.core.constant.AggregationType;
import com.orange.admin.common.core.constant.GlobalDeletedFlag;
import com.orange.admin.common.core.object.GlobalThreadLocal;
import com.orange.admin.common.core.object.MyWhereCriteria;
import com.orange.admin.common.core.object.Tuple2;
import com.orange.admin.common.core.util.ApplicationContextHolder;
import com.orange.admin.common.core.util.MyModelUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import tk.mybatis.mapper.entity.Example;
import cn.hutool.core.util.ReflectUtil;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.*;

/**
 * 所有Service的基类。
 *
 * @param <M> Model对象的类型。
 * @param <K> Model对象主键的类型。
 * @author Stephen.Liu
 * @date 2020-04-11
 */
@Slf4j
public abstract class BaseService<M, K> {

    /**
     * 当前Service关联的主Model实体对象的Class。
     */
    protected Class<M> modelClass;
    /**
     * 当前Service关联的主Model实体对象的实际表名称。
     */
    protected String tableName;
    /**
     * 当前Service关联的主Model对象主键字段名称。
     */
    protected String idFieldName;
    /**
     * 当前Service关联的主数据表中数据字段名称。
     */
    protected String idColumnName;
    /**
     * 当前Service关联的主Model对象逻辑删除字段名称。
     */
    protected String deletedFlagFieldName;
    /**
     * 当前Service关联的主数据表中逻辑删除字段名称。
     */
    protected String deletedFlagColumnName;
    /**
     * 当前Job服务源主表Model对象最后更新时间字段名称。
     */
    protected String updateTimeFieldName;
    /**
     * 当前Job服务源主表Model对象最后更新时间列名称。
     */
    protected String updateTimeColumnName;
    /**
     * 当前Service关联的主Model对象主键字段反射对象。
     */
    protected Field idField;
    /**
     * 当前Service关联的主Model对象逻辑删除字段反射对象。
     */
    protected Field deletedFlagField;
    /**
     * 当前Service关联的主Model对象逻辑字段赋值方法的反射对象。
     */
    private Method setDeletedFlagMethod;
    /**
     * 当前Service关联的主Model对象的所有字典关联的结构列表，该字段在系统启动阶段一次性预加载，提升运行时效率。
     */
    private List<RelationStruct> relationDictStructList;
    /**
     * 当前Service关联的主Model对象的所有常量字典关联的结构列表，该字段在系统启动阶段一次性预加载，提升运行时效率。
     */
    private List<RelationStruct> relationConstDictStructList;
    /**
     * 当前Service关联的主Model对象的所有一对一关联的结构列表，该字段在系统启动阶段一次性预加载，提升运行时效率。
     */
    private List<RelationStruct> relationOneToOneStructList;
    /**
     * 当前Service关联的主Model对象的所有一对多聚合关联的结构列表，该字段在系统启动阶段一次性预加载，提升运行时效率。
     */
    private List<RelationStruct> relationOneToManyAggrStructList;
    /**
     * 当前Service关联的主Model对象的所有多对多聚合关联的结构列表，该字段在系统启动阶段一次性预加载，提升运行时效率。
     */
    private List<RelationStruct> relationManyToManyAggrStructList;

    private static final String GROUPED_KEY = "groupedKey";
    private static final String AGGREGATED_VALUE = "aggregatedValue";

    /**
     * 构造函数，在实例化的时候，一次性完成所有有关主Model对象信息的加载。
     */
    @SuppressWarnings("unchecked")
    public BaseService() {
        modelClass = (Class<M>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
        this.tableName = modelClass.getAnnotation(Table.class).name();
        Field[] fields = ReflectUtil.getFields(modelClass);
        for (Field field : fields) {
            if (idFieldName == null) {
                if (null != field.getAnnotation(Id.class)) {
                    idFieldName = field.getName();
                    idField = field;
                    Column c = field.getAnnotation(Column.class);
                    idColumnName = c == null ? idFieldName : c.name();
                }
            }
            if (updateTimeFieldName == null) {
                if (null != field.getAnnotation(JobUpdateTimeColumn.class)) {
                    updateTimeFieldName = field.getName();
                    Column c = field.getAnnotation(Column.class);
                    updateTimeColumnName = c == null ? updateTimeFieldName : c.name();
                }
            }
            if (deletedFlagFieldName == null) {
                if (null != field.getAnnotation(DeletedFlagColumn.class)) {
                    deletedFlagFieldName = field.getName();
                    Column c = field.getAnnotation(Column.class);
                    deletedFlagColumnName = c == null ? deletedFlagFieldName : c.name();
                    deletedFlagField = field;
                    setDeletedFlagMethod = ReflectUtil.getMethod(
                            modelClass, "set" + StringUtils.capitalize(deletedFlagFieldName), Integer.class);
                }
            }
        }
    }

    /**
     * 获取子类中注入的Mapper类。
     *
     * @return 子类中注入的Mapper类。
     */
    protected abstract BaseDaoMapper<M> mapper();

    /**
     * 判断指定字段的数据是否存在，且仅仅存在一条记录。
     * 如果是基于主键的过滤，会直接调用existId过滤函数，提升性能。在有缓存的场景下，也可以利用缓存。
     *
     * @param fieldName  待过滤的字段名(Java 字段)。
     * @param fieldValue 字段值。
     * @return 存在且仅存在一条返回true，否则false。
     */
    @SuppressWarnings("unchecked")
    public boolean existOne(String fieldName, Object fieldValue) {
        if (fieldName.equals(this.idFieldName)) {
            return this.existId((K) fieldValue);
        }
        Example e = new Example(modelClass);
        e.createCriteria().andEqualTo(fieldName, fieldValue);
        return mapper().selectByExample(e).size() == 1;
    }

    /**
     * 判断主键Id关联的数据是否存在。
     *
     * @param id 主键Id。
     * @return 存在返回true，否则false。
     */
    public boolean existId(K id) {
        return getById(id) != null;
    }

    /**
     * 获取主键Id关联的数据。
     *
     * @param id 主键Id。
     * @return 主键关联的数据，不存在返回null。
     */
    public M getById(K id) {
        if (deletedFlagFieldName == null) {
            return mapper().selectByPrimaryKey(id);
        }
        Example e = new Example(modelClass);
        e.createCriteria()
                .andEqualTo(this.idFieldName, id)
                .andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
        return mapper().selectOneByExample(e);
    }

    /**
     * 获取主表的查询结果，以及主表关联的字典数据和一对一从表数据，以及一对一从表的字典数据。
     *
     * @param id 主表主键Id。
     * @return 查询结果对象。
     */
    public M getByIdWithRelation(K id) {
        return this.buildAllRelationForData(
                this.getById(id), false, buildAggregationAdditionalWhereCriteria());
    }

    /**
     * 获取所有数据。
     *
     * @return 返回所有数据。
     */
    public List<M> getAllList() {
        if (deletedFlagFieldName == null) {
            return mapper().selectAll();
        }
        Example e = new Example(modelClass);
        e.createCriteria().andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
        return mapper().selectByExample(e);
    }

    /**
     * 获取所有主数据，及其关联数据。
     *
     * @param dictOnly true 将只是关联字典数据，false将同时关联字典数据和一对一关联数据。
     * @return 返回所有主数据，及其关联数据。
     */
    public List<M> getAllListWithRelation(boolean dictOnly) {
        List<M> resultList = getAllList();
        this.buildRelationForDataList(resultList, dictOnly);
        return resultList;
    }

    /**
     * 获取排序后所有数据。
     *
     * @param orderByProperties 需要排序的字段属性，这里使用Java对象中的属性名，而不是数据库字段名。
     * @return 返回排序后所有数据。
     */
    public List<M> getAllListByOrder(String... orderByProperties) {
        Example e = new Example(modelClass);
        for (String orderByProperty : orderByProperties) {
            e.orderBy(orderByProperty);
        }
        if (deletedFlagFieldName != null) {
            e.and().andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
        }
        return mapper().selectByExample(e);
    }

    /**
     * 判断参数值列表中的所有数据，是否全部存在。另外，keyName字段在数据表中必须是唯一键值，否则返回结果会出现误判。
     *
     * @param inFilterField  待校验的数据字段，这里使用Java对象中的属性，如courseId，而不是数据字段名course_id
     * @param inFilterValues 数据值列表。
     * @return 全部存在返回true，否则false。
     */
    public <T> boolean existUniqueKeyList(String inFilterField, Set<T> inFilterValues) {
        if (CollectionUtils.isEmpty(inFilterValues)) {
            return false;
        }
        Example e = this.makeDefaultInListExample(inFilterField, inFilterValues, null);
        if (deletedFlagFieldName != null) {
            e.and().andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
        }
        return mapper().selectCountByExample(e) == inFilterValues.size();
    }

    /**
     * 获取所有数据的指定字段的数据列表。
     *
     * @param getterFunc 指定字段的getter方法。
     * @param <T>        指定字段的类型。
     * @return 指定字段的列表。
     */
    public <T> List<T> getAllListWithField(Function<M, T> getterFunc) {
        List<M> allList = this.getAllList();
        // 即使没有符合filter条件的item存在，也会返回空列表对象，而不是null。
        return allList.stream().filter(x -> getterFunc.apply(x) != null).map(getterFunc).collect(toList());
    }

    /**
     * 返回符合主键 in (idValues) 条件的所有数据。
     *
     * @param idValues 主键值集合。
     * @return 检索后的数据列表。
     */
    public List<M> getInList(Set<K> idValues) {
        return this.getInList(idFieldName, idValues, (String) null);
    }

    /**
     * 通过getIdFunc函数，获取主对象列表masterList中的主键列表。在基于该Id列表作为主键查询的in list条件，获取数据。
     *
     * @param masterList 主对象列表，通过应用getIdFunc函数，获取查询条件中的主键in list条件。
     * @param getIdFunc  获取主对象中符合查询条件主键的id值。
     * @return 检索后的数据列表。
     */
    public <T> List<M> getInList(List<T> masterList, Function<T, K> getIdFunc) {
        // 即使没有符合filter条件的item存在，也会返回空列表对象，而不是null。
        return this.getInList(masterList.stream()
                .filter(x -> getIdFunc.apply(x) != null).map(getIdFunc).collect(toSet()));
    }

    /**
     * 返回符合 inFilterField in (inFilterValues) 条件的所有数据。
     *
     * @param inFilterField  参与(In-list)过滤的Java字段。
     * @param inFilterValues 参与(In-list)过滤的Java字段值集合。
     * @return 检索后的数据列表。
     */
    public <T> List<M> getInList(String inFilterField, Set<T> inFilterValues) {
        return this.getInList(inFilterField, inFilterValues, (String) null);
    }

    /**
     * 返回符合 inFilterField in (inFilterValues) 条件的所有数据，并根据orderBy字段排序。
     *
     * @param inFilterField  参与(In-list)过滤的Java字段。
     * @param inFilterValues 参与(In-list)过滤的Java字段值集合。
     * @param orderBy        排序字段。
     * @return 检索后的数据列表。
     */
    public <T> List<M> getInList(String inFilterField, Set<T> inFilterValues, String orderBy) {
        if (CollectionUtils.isEmpty(inFilterValues)) {
            return new LinkedList<>();
        }
        Example e = this.makeDefaultInListExample(inFilterField, inFilterValues, orderBy);
        if (deletedFlagFieldName != null) {
            e.and().andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
        }
        return mapper().selectByExample(e);
    }

    /**
     * 基于对象集合(masterList)，并通过masterIdFunction函数对象获取property字段的数据列表，
     * inFilterField in (inFilterValues)的所有数据。
     *
     * @param inFilterField    参与(In-list)过滤的Java字段。
     * @param masterList       主对象数据集合。
     * @param masterIdFunction 获取主对象中，property字段数值的函数对象。
     * @param <T>              主对象类型。
     * @return 检索后的数据列表。
     */
    public <T> List<M> getInList(String inFilterField, Collection<T> masterList, Function<T, K> masterIdFunction) {
        // 即使没有符合filter条件的item存在，也会返回空列表对象，而不是null。
        return this.getInList(inFilterField, masterList.stream()
                .filter(x -> masterIdFunction.apply(x) != null).map(masterIdFunction).collect(toSet()));
    }

    /**
     * 用参数对象作为过滤条件，获取数据数量。
     *
     * @param filter 该方法基于mybatis 通用mapper，过滤对象中，只有被赋值的字段，才会成为where中的条件。
     * @return 返回过滤后的数据数量。
     */
    public int getCountByFilter(M filter) {
        if (deletedFlagFieldName == null) {
            return mapper().selectCount(filter);
        }
        try {
            setDeletedFlagMethod.invoke(filter, GlobalDeletedFlag.NORMAL);
            return mapper().selectCount(filter);
        } catch (Exception e) {
            log.error("Failed to call reflection code.", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 用参数对象作为过滤条件，判断是否存在过滤数据。
     *
     * @param filter 该方法基于mybatis 通用mapper，过滤对象中，只有被赋值的字段，才会成为where中的条件。
     * @return 存在返回true，否则false。
     */
    public boolean existByFilter(M filter) {
        return this.getCountByFilter(filter) > 0;
    }

    /**
     * 用参数对象作为过滤条件，获取查询结果。
     *
     * @param filter 该方法基于mybatis的通用mapper。如果参数为null，则返回全部数据。
     * @return 返回过滤后的数据。
     */
    public List<M> getListByFilter(M filter) {
        if (filter == null) {
            return this.getAllList();
        }
        if (deletedFlagFieldName == null) {
            return mapper().select(filter);
        }
        try {
            setDeletedFlagMethod.invoke(filter, GlobalDeletedFlag.NORMAL);
            return mapper().select(filter);
        } catch (Exception ex) {
            log.error("Failed to call reflection code.", ex);
            throw new RuntimeException(ex);
        }
    }

    /**
     * 用参数对象作为过滤条件，获取查询结果。
     *
     * @param filter  该方法基于mybatis的通用mapper。如果参数为null，则返回全部数据。
     * @param orderBy SQL中ORDER BY从句。
     * @return 返回过滤后的数据。
     */
    public List<M> getListByFilter(M filter, String orderBy) {
        Example e = new Example(modelClass);
        if (StringUtils.isNotBlank(orderBy)) {
            e.setOrderByClause(orderBy);
        }
        if (filter != null) {
            Example.Criteria c = e.createCriteria();
            Field[] fields = ReflectUtil.getFields(modelClass);
            for (Field field : fields) {
                if (field.getAnnotation(Transient.class) != null) {
                    continue;
                }
                int modifiers = field.getModifiers();
                // transient类型的字段不能作为查询条件
                if ((modifiers & 128) == 0) {
                    if (field.getName().equals(deletedFlagFieldName)) {
                        c.andEqualTo(deletedFlagFieldName, GlobalDeletedFlag.NORMAL);
                    } else {
                        field.setAccessible(true);
                        try {
                            Object o = field.get(filter);
                            if (o != null) {
                                c.andEqualTo(field.getName(), field.get(filter));
                            }
                        } catch (IllegalAccessException ex) {
                            log.error("Failed to call reflection code.", ex);
                            throw new RuntimeException(ex);
                        }
                    }
                }
            }
        }
        return mapper().selectByExample(e);
    }

    /**
     * 用参数对象作为过滤条件，获取查询结果。同时组装实体对象中基于RelationXXXX注解关联的数据。
     *
     * @param filter 该方法基于mybatis的通用mapper。如果参数为null，则返回全部数据。
     * @return 返回过滤后的数据。
     */
    public List<M> getListWithRelationByFilter(M filter) {
        List<M> resultList = this.getListByFilter(filter);
        Map<String, List<MyWhereCriteria>> criteriaMap = buildAggregationAdditionalWhereCriteria();
        this.buildAllRelationForDataList(resultList, false, criteriaMap);
        return resultList;
    }

    /**
     * 用参数对象作为过滤条件，获取查询结果。同时组装实体对象中基于RelationXXXX注解关联的数据。
     *
     * @param filter  该方法基于mybatis的通用mapper。如果参数为null，则返回全部数据。
     * @param orderBy SQL中ORDER BY从句。
     * @return 返回过滤后的数据。
     */
    public List<M> getListWithRelationByFilter(M filter, String orderBy) {
        List<M> resultList = this.getListByFilter(filter, orderBy);
        Map<String, List<MyWhereCriteria>> criteriaMap = buildAggregationAdditionalWhereCriteria();
        this.buildAllRelationForDataList(resultList, false, criteriaMap);
        return resultList;
    }

    /**
     * 获取父主键Id下的所有子数据列表。
     *
     * @param parentIdFieldName 父主键字段名字，如"courseId"。
     * @param parentId          父主键的值。
     * @return 父主键Id下的所有子数据列表。
     */
    public List<M> getListByParentId(String parentIdFieldName, K parentId) {
        Example e = new Example(modelClass);
        if (parentId != null) {
            e.createCriteria().andEqualTo(parentIdFieldName, parentId);
        } else {
            e.createCriteria().andIsNull(parentIdFieldName);
        }
        return mapper().selectByExample(e);
    }

    /**
     * 根据指定的显示字段列表、过滤条件字符串和分组字符串，返回聚合计算后的查询结果。(基本是内部框架使用，不建议外部接口直接使用)。
     *
     * @param selectFields 选择的字段列表，多个字段逗号分隔。
     *                     NOTE: 如果数据表字段和Java对象字段名字不同，Java对象字段应该以别名的形式出现。
     *                     如: table_column_name modelFieldName。否则无法被反射回Bean对象。
     * @param whereClause  SQL常量形式的条件从句。
     * @param groupBy      SQL常量形式分组字段列表，逗号分隔。
     * @return 聚合计算后的数据结果集。
     */
    public List<Map<String, Object>> getGroupedListByCondition(
            String selectFields, String whereClause, String groupBy) {
        return mapper().getGroupedListByCondition(tableName, selectFields, whereClause, groupBy);
    }

    /**
     * 根据指定的显示字段列表、过滤条件字符串和排序字符串，返回查询结果。(基本是内部框架使用，不建议外部接口直接使用)。
     *
     * @param selectList   选择的Java字段列表。如果为空表示返回全部字段。
     * @param whereClause  SQL常量形式的条件从句。
     * @param orderBy      SQL常量形式排序字段列表，逗号分隔。
     * @return 查询结果。
     */
    public List<M> getListByCondition(List<String> selectList, String whereClause, String orderBy) {
        Example e = new Example(modelClass);
        if (CollectionUtils.isNotEmpty(selectList)) {
            String[] selectFields = new String[selectList.size()];
            selectList.toArray(selectFields);
            e.selectProperties(selectFields);
        }
        if (StringUtils.isNotBlank(orderBy)) {
            e.setOrderByClause(orderBy);
        }
        if (StringUtils.isNotBlank(whereClause)) {
            e.createCriteria().andCondition(whereClause);
        }
        return mapper().selectByExample(e);
    }

    /**
     * 用指定过滤条件，计算记录数量。(基本是内部框架使用，不建议外部接口直接使用)。
     *
     * @param whereClause SQL常量形式的条件从句。
     * @return 返回过滤后的数据数量。
     */
    public Integer getCountByCondition(String whereClause) {
        return mapper().getCountByCondition(this.tableName, whereClause);
    }

    /**
     * 集成所有与主表实体对象相关的关联数据列表。包括一对一、字典、一对多和多对多聚合运算等。
     * 也可以根据实际需求，单独调用该函数所包含的各个数据集成函数。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param resultList      主表实体对象列表。数据集成将直接作用于该对象列表。
     * @param dictOnly        是否只是集成字典，包括注解RelationDict和RelationConstDict标注的字段。
     * @param criteriaListMap 仅仅用于一对多和多对多聚合计算的附加过滤条件。如果没有可以为NULL。
     */
    public void buildAllRelationForDataList(
            List<? extends M> resultList, boolean dictOnly, Map<String, List<MyWhereCriteria>> criteriaListMap) {
        // 集成本地一对一和字段级别的数据关联。
        this.buildRelationForDataList(resultList, dictOnly);
        if (!dictOnly) {
            // 组装本地聚合计算关联数据
            this.buildAggregationRelationForDataList(resultList, criteriaListMap);
        }
    }

    /**
     * 集成所有与主表实体对象相关的关联数据对象。包括一对一、字典、一对多和多对多聚合运算等。
     * 也可以根据实际需求，单独调用该函数所包含的各个数据集成函数。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param dataObject      主表实体对象。数据集成将直接作用于该对象。
     * @param dictOnly        是否只是集成字典，包括注解RelationDict和RelationConstDict标注的字段。
     * @param criteriaListMap 仅仅用于一对多和多对多聚合计算的附加过滤条件。如果没有可以为NULL。
     * @param <T>             实体对象类型。
     * @return 集成后的原实体对象。
     */
    public <T extends M> T buildAllRelationForData(
            T dataObject, boolean dictOnly, Map<String, List<MyWhereCriteria>> criteriaListMap) {
        // 集成本地一对一和字段级别的数据关联。
        this.buildRelationForData(dataObject, dictOnly);
        if (!dictOnly) {
            // 组装本地聚合计算关联数据
            this.buildAggregationRelationForData(dataObject, criteriaListMap);
        }
        return dataObject;
    }

    /**
     * 为参数列表数据集成本地静态字典关联数据。
     *
     * @param resultList 主表数据列表。
     */
    public void buildConstDictForDataList(List<? extends M> resultList) {
        if (CollectionUtils.isNotEmpty(this.relationConstDictStructList)) {
            for (RelationStruct relationStruct : this.relationConstDictStructList) {
                for (M dataObject : resultList) {
                    Object id = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                    if (id != null) {
                        String name = relationStruct.dictMap.get(id);
                        if (name != null) {
                            Map<String, Object> dictMap = new HashMap<>(2);
                            dictMap.put("id", id);
                            dictMap.put("name", name);
                            ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, dictMap);
                        }
                    }
                }
            }
        }
    }

    /**
     * 为参数实体对象数据集成本地静态字典关联数据。
     *
     * @param dataObject 实体对象。
     */
    public <T extends M> void buildConstDictForData(T dataObject) {
        if (CollectionUtils.isNotEmpty(this.relationConstDictStructList)) {
            for (RelationStruct relationStruct : this.relationConstDictStructList) {
                Object id = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                if (id != null) {
                    String name = relationStruct.dictMap.get(id);
                    if (name != null) {
                        Map<String, Object> dictMap = new HashMap<>(2);
                        dictMap.put("id", id);
                        dictMap.put("name", name);
                        ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, dictMap);
                    }
                }
            }
        }
    }

    /**
     * 集成字典关联数据列表和一对一从表关联数据列表。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param resultList 主表数据列表。数据集成将直接作用于该对象列表。
     * @param dictOnly   是否只是关联字典数据。
     */
    public void buildRelationForDataList(List<? extends M> resultList, boolean dictOnly) {
        if (CollectionUtils.isEmpty(resultList)) {
            return;
        }
        boolean dataPermEnabled = GlobalThreadLocal.setDataPerm(false);
        if (!dictOnly) {
            if (CollectionUtils.isNotEmpty(this.relationOneToOneStructList)) {
                for (RelationStruct relationStruct : this.relationOneToOneStructList) {
                    Set<Object> masterIdSet = resultList.stream()
                            .map(obj -> ReflectUtil.getFieldValue(obj, relationStruct.masterIdField))
                            .filter(Objects::nonNull)
                            .collect(toSet());
                    if (CollectionUtils.isNotEmpty(masterIdSet)) {
                        BaseService<Object, Object> relationService = relationStruct.service;
                        List<Object> relationList =
                                relationService.getInList(relationStruct.relationOneToOne.slaveIdField(), masterIdSet);
                        // 仅仅当需要加载从表字典关联时，才去加载。
                        if (relationStruct.relationOneToOne.loadSlaveDict() && CollectionUtils.isNotEmpty(relationList)) {
                            relationService.buildRelationForDataList(relationList, true);
                            MyModelUtil.makeOneToOneRelation(
                                    modelClass, resultList, relationList, relationStruct.relationField.getName());
                        }
                    }
                }
            }
        }
        // 构建常量字典关联关系
        this.buildConstDictForDataList(resultList);
        if (CollectionUtils.isNotEmpty(this.relationDictStructList)) {
            for (RelationStruct relationStruct : this.relationDictStructList) {
                List<Object> relationList = null;
                if (!dictOnly && relationStruct.equalOneToOneRelationField != null) {
                    relationList = resultList.stream()
                            .map(obj -> ReflectUtil.getFieldValue(obj, relationStruct.equalOneToOneRelationField))
                            .filter(Objects::nonNull)
                            .collect(toList());
                } else {
                    String slaveId = relationStruct.relationDict.slaveIdField();
                    Set<Object> masterIdSet = resultList.stream()
                            .map(obj -> ReflectUtil.getFieldValue(obj, relationStruct.masterIdField))
                            .filter(Objects::nonNull)
                            .collect(toSet());
                    if (CollectionUtils.isNotEmpty(masterIdSet)) {
                        relationList = relationStruct.service.getInList(slaveId, masterIdSet);
                    }
                }
                MyModelUtil.makeDictRelation(
                        modelClass, resultList, relationList, relationStruct.relationField.getName());
            }
        }
        GlobalThreadLocal.setDataPerm(dataPermEnabled);
    }

    /**
     * 集成字典关联数据和一对一从表关联数据。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param dataObject 主表数据对象。数据集成将直接作用于该对象。
     * @param dictOnly   是否只是关联字典数据。
     * @return 关联后的主表数据对象。
     */
    public <T extends M> T buildRelationForData(T dataObject, boolean dictOnly) {
        if (dataObject == null) {
            return dataObject;
        }
        boolean dataPermEnabled = GlobalThreadLocal.setDataPerm(false);
        if (!dictOnly) {
            if (CollectionUtils.isNotEmpty(this.relationOneToOneStructList)) {
                for (RelationStruct relationStruct : this.relationOneToOneStructList) {
                    Object id = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                    if (id != null) {
                        BaseService<Object, Object> relationService = relationStruct.service;
                        Object relationObject = relationService.getById(id);
                        // 仅仅当需要加载从表字典关联时，才去加载。
                        if (relationStruct.relationOneToOne.loadSlaveDict() && relationObject != null) {
                            relationService.buildRelationForData(relationObject, true);
                            ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, relationObject);
                        }
                    }
                }
            }
        }
        // 构建常量字典关联关系
        this.buildConstDictForData(dataObject);
        if (CollectionUtils.isNotEmpty(this.relationDictStructList)) {
            for (RelationStruct relationStruct : this.relationDictStructList) {
                Object relationObject = null;
                if (!dictOnly && relationStruct.equalOneToOneRelationField != null) {
                    relationObject = ReflectUtil.getFieldValue(dataObject, relationStruct.equalOneToOneRelationField);
                } else {
                    Object id = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                    if (id != null) {
                        relationObject = relationStruct.service.getById(id);
                    }
                }
                MyModelUtil.makeDictRelation(
                        modelClass, dataObject, relationObject, relationStruct.relationField.getName());
            }
        }
        GlobalThreadLocal.setDataPerm(dataPermEnabled);
        return dataObject;
    }

    /**
     * 集成本地服务中，多个表之间的聚合计算关系，主要覆盖一对多和多对多两种场景。
     * 其中一对多关系，比如课程表的章节统计字段sectionCount，可能依赖于课程章节表中关联章节的数量。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param resultList      主数据表结果集。数据集成将直接作用于该对象列表。
     * @param criteriaListMap 自定义的过滤条件列表。每个key对应不同的聚合关联字段名称，既注解应用的Java字段，如courseSectionCount。
     */
    public void buildAggregationRelationForDataList(
            List<? extends M> resultList, Map<String, List<MyWhereCriteria>> criteriaListMap) {
        if (CollectionUtils.isEmpty(resultList)) {
            return;
        }
        boolean dataPermEnabled = GlobalThreadLocal.setDataPerm(false);
        // 处理多对多场景下，根据主表的结果，进行从表聚合数据的计算。
        if (CollectionUtils.isNotEmpty(this.relationManyToManyAggrStructList)) {
            if (criteriaListMap == null) {
                criteriaListMap = new HashMap<>(this.relationManyToManyAggrStructList.size());
            }
            for (RelationStruct relationStruct : this.relationManyToManyAggrStructList) {
                Set<Object> masterIdSet = resultList.stream()
                        .map(obj -> ReflectUtil.getFieldValue(obj, relationStruct.masterIdField))
                        .filter(Objects::nonNull)
                        .collect(toSet());
                if (CollectionUtils.isNotEmpty(masterIdSet)) {
                    RelationManyToManyAggregation relation = relationStruct.relationManyToManyAggregation;
                    // 提取关联中用到的各种字段和表数据。
                    String slaveTable = MyModelUtil.mapToTableName(relation.slaveModelClass());
                    String relationTable = MyModelUtil.mapToTableName(relation.relationModelClass());
                    String relationMasterColumn =
                            MyModelUtil.mapToColumnName(relation.relationMasterIdField(), relation.relationModelClass());
                    String relationSlaveColumn =
                            MyModelUtil.mapToColumnName(relation.relationSlaveIdField(), relation.relationModelClass());
                    String slaveColumn =
                            MyModelUtil.mapToColumnName(relation.slaveIdField(), relation.slaveModelClass());
                    // 判断是否只需要关联中间表即可，从而提升查询统计的效率。
                    // 1. 统计字段为中间表字段。2. 自定义过滤条件中没有基于从表字段的过滤条件。
                    boolean onlySelectRelationTable =
                            relation.aggregationModelClass().equals(relation.relationModelClass());
                    if (onlySelectRelationTable && MapUtils.isNotEmpty(criteriaListMap)) {
                        List<MyWhereCriteria> criteriaList =
                                criteriaListMap.get(relationStruct.relationField.getName());
                        if (CollectionUtils.isNotEmpty(criteriaList)) {
                            for (MyWhereCriteria whereCriteria : criteriaList) {
                                if (whereCriteria.getModelClazz().equals(relation.slaveModelClass())) {
                                    onlySelectRelationTable = false;
                                    break;
                                }
                            }
                        }
                    }
                    String aggregationTable =
                            relation.aggregationModelClass().equals(relation.relationModelClass()) ? relationTable : slaveTable;
                    Tuple2<String, String> selectAndGroupByTuple = makeSelectListAndGroupByClause(
                            relationTable, relationMasterColumn, relation.aggregationModelClass(),
                            aggregationTable, relation.aggregationField(), relation.aggregationType());
                    String selectList = selectAndGroupByTuple.getFirst();
                    String groupBy = selectAndGroupByTuple.getSecond();
                    // 构建多表关联的where语句
                    StringBuilder whereClause = new StringBuilder(256);
                    // 如果需要从表聚合计算或参与过滤，则需要把中间表和从表之间的关联条件加上。
                    if (!onlySelectRelationTable) {
                        whereClause.append(relationTable)
                                .append(".")
                                .append(relationSlaveColumn)
                                .append(" = ")
                                .append(slaveTable)
                                .append(".")
                                .append(slaveColumn);
                    } else {
                        whereClause.append("1 = 1");
                    }
                    List<MyWhereCriteria> criteriaList = criteriaListMap.get(relationStruct.relationField.getName());
                    if (criteriaList == null) {
                        criteriaList = new LinkedList<>();
                    }
                    MyWhereCriteria inlistFilter = new MyWhereCriteria();
                    inlistFilter.setCriteria(relation.relationModelClass(),
                            relation.relationMasterIdField(), MyWhereCriteria.OPERATOR_IN, masterIdSet);
                    criteriaList.add(inlistFilter);
                    if (StringUtils.isNotBlank(relationStruct.service.deletedFlagFieldName)) {
                        MyWhereCriteria deleteFilter = new MyWhereCriteria();
                        deleteFilter.setCriteria(
                                relation.slaveModelClass(),
                                relationStruct.service.deletedFlagFieldName,
                                MyWhereCriteria.OPERATOR_EQUAL,
                                GlobalDeletedFlag.NORMAL);
                        criteriaList.add(deleteFilter);
                    }
                    String criteriaString = MyWhereCriteria.getCriteriaString(criteriaList);
                    whereClause.append(" AND ").append(criteriaString);
                    StringBuilder tableNames = new StringBuilder(64);
                    tableNames.append(relationTable);
                    if (!onlySelectRelationTable) {
                        tableNames.append(", ").append(slaveTable);
                    }
                    List<Map<String, Object>> aggregationMapList = mapper().getGroupedListByCondition(
                            tableNames.toString(), selectList, whereClause.toString(), groupBy);
                    doMakeLocalAggregationData(aggregationMapList, resultList, relationStruct);
                }
            }
        }
        // 处理多一多场景下，根据主表的结果，进行从表聚合数据的计算。
        if (CollectionUtils.isNotEmpty(this.relationOneToManyAggrStructList)) {
            if (criteriaListMap == null) {
                criteriaListMap = new HashMap<>(relationOneToManyAggrStructList.size());
            }
            for (RelationStruct relationStruct : this.relationOneToManyAggrStructList) {
                Set<Object> masterIdSet = resultList.stream()
                        .map(obj -> ReflectUtil.getFieldValue(obj, relationStruct.masterIdField))
                        .filter(Objects::nonNull)
                        .collect(toSet());
                if (CollectionUtils.isNotEmpty(masterIdSet)) {
                    RelationOneToManyAggregation relation = relationStruct.relationOneToManyAggregation;
                    // 开始获取后面所需的各种关联数据。此部分今后可以移植到缓存中，无需每次计算。
                    String slaveTable = MyModelUtil.mapToTableName(relation.slaveModelClass());
                    String slaveColumnName =
                            MyModelUtil.mapToColumnName(relation.slaveIdField(), relation.slaveModelClass());
                    Tuple2<String, String> selectAndGroupByTuple = makeSelectListAndGroupByClause(
                            slaveTable, slaveColumnName, relation.slaveModelClass(),
                            slaveTable, relation.aggregationField(), relation.aggregationType());
                    String selectList = selectAndGroupByTuple.getFirst();
                    String groupBy = selectAndGroupByTuple.getSecond();
                    List<MyWhereCriteria> criteriaList = criteriaListMap.get(relationStruct.relationField.getName());
                    if (criteriaList == null) {
                        criteriaList = new LinkedList<>();
                    }
                    MyWhereCriteria inlistFilter = new MyWhereCriteria();
                    inlistFilter.setCriteria(relation.slaveModelClass(),
                            relation.slaveIdField(), MyWhereCriteria.OPERATOR_IN, masterIdSet);
                    criteriaList.add(inlistFilter);
                    if (StringUtils.isNotBlank(relationStruct.service.deletedFlagFieldName)) {
                        MyWhereCriteria deleteFilter = new MyWhereCriteria();
                        deleteFilter.setCriteria(
                                relation.slaveModelClass(),
                                relationStruct.service.deletedFlagFieldName,
                                MyWhereCriteria.OPERATOR_EQUAL,
                                GlobalDeletedFlag.NORMAL);
                        criteriaList.add(deleteFilter);
                    }
                    String criteriaString = MyWhereCriteria.getCriteriaString(criteriaList);
                    List<Map<String, Object>> aggregationMapList =
                            mapper().getGroupedListByCondition(slaveTable, selectList, criteriaString, groupBy);
                    doMakeLocalAggregationData(aggregationMapList, resultList, relationStruct);
                }
            }
        }
        GlobalThreadLocal.setDataPerm(dataPermEnabled);
    }

    /**
     * 集成本地服务中，多个表之间的聚合计算关系，主要覆盖一对多和多对多两种场景。
     * 其中一对多关系，比如课程表的章节统计字段sectionCount，可能依赖于课程章节表中关联章节的数量。
     * NOTE: 该方法内执行的SQL将禁用数据权限过滤。
     *
     * @param dataObject      主表对象。数据集成将直接作用于该对象。
     * @param criteriaListMap 自定义的过滤条件列表。每个key对应不同的聚合关联字段名称，既注解应用的Java字段，如courseSectionCount。
     * @param <T>             参数类型。
     * @return 集成后的主表对象。
     */
    public <T extends M> T buildAggregationRelationForData(T dataObject, Map<String, List<MyWhereCriteria>> criteriaListMap) {
        if (dataObject == null) {
            return null;
        }
        boolean dataPermEnabled = GlobalThreadLocal.setDataPerm(false);
        // 开始处理多对多场景。
        if (CollectionUtils.isNotEmpty(this.relationManyToManyAggrStructList)) {
            if (criteriaListMap == null) {
                criteriaListMap = new HashMap<>(relationOneToManyAggrStructList.size());
            }
            for (RelationStruct relationStruct : this.relationManyToManyAggrStructList) {
                Object masterIdValue = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                if (masterIdValue != null) {
                    RelationManyToManyAggregation relation = relationStruct.relationManyToManyAggregation;
                    String slaveTable = MyModelUtil.mapToTableName(relation.slaveModelClass());
                    String relationTable = MyModelUtil.mapToTableName(relation.relationModelClass());
                    String relationMasterColumn =
                            MyModelUtil.mapToColumnName(relation.relationMasterIdField(), relation.relationModelClass());
                    String relationSlaveColumn =
                            MyModelUtil.mapToColumnName(relation.relationSlaveIdField(), relation.relationModelClass());
                    String slaveColumn = MyModelUtil.mapToColumnName(relation.slaveIdField(), relation.slaveModelClass());
                    // 判断是否只需要关联中间表即可，从而提升查询统计的效率。
                    // 1. 统计字段为中间表字段。2. 自定义过滤条件中没有基于从表字段的过滤条件。
                    boolean onlySelectRelationTable = relation.aggregationModelClass().equals(relation.relationModelClass());
                    if (onlySelectRelationTable && MapUtils.isNotEmpty(criteriaListMap)) {
                        List<MyWhereCriteria> criteriaList =
                                criteriaListMap.get(relationStruct.relationField.getName());
                        if (CollectionUtils.isNotEmpty(criteriaList)) {
                            for (MyWhereCriteria whereCriteria : criteriaList) {
                                if (whereCriteria.getModelClazz().equals(relation.slaveModelClass())) {
                                    onlySelectRelationTable = false;
                                    break;
                                }
                            }
                        }
                    }
                    String aggregationTable =
                            relation.aggregationModelClass().equals(relation.relationModelClass()) ? relationTable : slaveTable;
                    Tuple2<String, String> selectAndGroupByTuple = makeSelectListAndGroupByClause(
                            relationTable, relationMasterColumn, relation.aggregationModelClass(),
                            aggregationTable, relation.aggregationField(), relation.aggregationType());
                    String selectList = selectAndGroupByTuple.getFirst();
                    String groupBy = selectAndGroupByTuple.getSecond();
                    // 组装过滤条件
                    StringBuilder whereClause = new StringBuilder(256);
                    whereClause.append(relationTable).append(".").append(relationMasterColumn);
                    if (masterIdValue instanceof Number) {
                        whereClause.append(" = ").append(masterIdValue);
                    } else {
                        whereClause.append(" = '").append(masterIdValue).append("'");
                    }
                    // 如果需要从表聚合计算或参与过滤，则需要把中间表和从表之间的关联条件加上。
                    if (!onlySelectRelationTable) {
                        whereClause.append(" AND ")
                                .append(relationTable)
                                .append(".")
                                .append(relationSlaveColumn)
                                .append(" = ")
                                .append(slaveTable)
                                .append(".")
                                .append(slaveColumn);
                    }
                    List<MyWhereCriteria> criteriaList = criteriaListMap.get(relationStruct.relationField.getName());
                    if (criteriaList == null) {
                        criteriaList = new LinkedList<>();
                    }
                    if (StringUtils.isNotBlank(relationStruct.service.deletedFlagFieldName)) {
                        MyWhereCriteria deleteFilter = new MyWhereCriteria();
                        deleteFilter.setCriteria(
                                relation.slaveModelClass(),
                                relationStruct.service.deletedFlagFieldName,
                                MyWhereCriteria.OPERATOR_EQUAL,
                                GlobalDeletedFlag.NORMAL);
                        criteriaList.add(deleteFilter);
                    }
                    if (CollectionUtils.isNotEmpty(criteriaList)) {
                        String criteriaString = MyWhereCriteria.getCriteriaString(criteriaList);
                        whereClause.append(" AND ").append(criteriaString);
                    }
                    StringBuilder tableNames = new StringBuilder(64);
                    tableNames.append(relationTable);
                    if (!onlySelectRelationTable) {
                        tableNames.append(", ").append(slaveTable);
                    }
                    List<Map<String, Object>> aggregationMapList = mapper().getGroupedListByCondition(
                            tableNames.toString(), selectList, whereClause.toString(), groupBy);
                    // 将查询后的结果回填到主表数据中。
                    if (CollectionUtils.isNotEmpty(aggregationMapList)) {
                        Object value = aggregationMapList.get(0).get(AGGREGATED_VALUE);
                        if (value != null) {
                            ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, value);
                        }
                    }
                }
            }
        }
        // 构建一对多场景
        if (CollectionUtils.isNotEmpty(this.relationOneToManyAggrStructList)) {
            if (criteriaListMap == null) {
                criteriaListMap = new HashMap<>(relationOneToManyAggrStructList.size());
            }
            for (RelationStruct relationStruct : this.relationOneToManyAggrStructList) {
                Object masterIdValue = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                if (masterIdValue != null) {
                    RelationOneToManyAggregation relation = relationStruct.relationOneToManyAggregation;
                    String slaveTable = MyModelUtil.mapToTableName(relation.slaveModelClass());
                    String slaveColumnName =
                            MyModelUtil.mapToColumnName(relation.slaveIdField(), relation.slaveModelClass());
                    Tuple2<String, String> selectAndGroupByTuple = makeSelectListAndGroupByClause(
                            slaveTable, slaveColumnName, relation.slaveModelClass(),
                            slaveTable, relation.aggregationField(), relation.aggregationType());
                    String selectList = selectAndGroupByTuple.getFirst();
                    String groupBy = selectAndGroupByTuple.getSecond();
                    StringBuilder whereClause = new StringBuilder(64);
                    if (masterIdValue instanceof Number) {
                        whereClause.append(slaveColumnName).append(" = ").append(masterIdValue);
                    } else {
                        whereClause.append(slaveColumnName).append(" = '").append(masterIdValue).append("'");
                    }
                    List<MyWhereCriteria> criteriaList = criteriaListMap.get(relationStruct.relationField.getName());
                    if (criteriaList == null) {
                        criteriaList = new LinkedList<>();
                    }
                    if (StringUtils.isNotBlank(relationStruct.service.deletedFlagFieldName)) {
                        MyWhereCriteria deleteFilter = new MyWhereCriteria();
                        deleteFilter.setCriteria(
                                relation.slaveModelClass(),
                                relationStruct.service.deletedFlagFieldName,
                                MyWhereCriteria.OPERATOR_EQUAL,
                                GlobalDeletedFlag.NORMAL);
                        criteriaList.add(deleteFilter);
                    }
                    if (CollectionUtils.isNotEmpty(criteriaList)) {
                        String criteriaString = MyWhereCriteria.getCriteriaString(criteriaList);
                        whereClause.append(" AND ").append(criteriaString);
                    }
                    // 获取分组聚合计算结果
                    List<Map<String, Object>> aggregationMapList = mapper().getGroupedListByCondition(
                            slaveTable, selectList, whereClause.toString(), groupBy);
                    // 将计算结果回填到主表关联字段
                    if (CollectionUtils.isNotEmpty(aggregationMapList)) {
                        Object value = aggregationMapList.get(0).get(AGGREGATED_VALUE);
                        if (value != null) {
                            ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, value);
                        }
                    }
                }
            }
        }
        GlobalThreadLocal.setDataPerm(dataPermEnabled);
        return dataObject;
    }

    /**
     * 仅仅在spring boot 启动后的监听器事件中调用，缓存所有service的关联关系，加速后续的数据绑定效率。
     */
    @SuppressWarnings("unchecked")
    public void loadRelationStruct() {
        Field[] fields = ReflectUtil.getFields(modelClass);
        for (Field f : fields) {
            RelationConstDict relationConstDict = f.getAnnotation(RelationConstDict.class);
            if (relationConstDict != null) {
                if (relationConstDictStructList == null) {
                    relationConstDictStructList = new LinkedList<>();
                }
                RelationStruct relationStruct = new RelationStruct();
                relationStruct.relationField = f;
                relationStruct.masterIdField = ReflectUtil.getField(modelClass, relationConstDict.masterIdField());
                Field dictMapField = ReflectUtil.getField(relationConstDict.constantDictClass(), "DICT_MAP");
                relationStruct.dictMap = (Map<Object, String>) ReflectUtil.getFieldValue(modelClass, dictMapField);
                relationConstDictStructList.add(relationStruct);
                continue;
            }
            RelationDict relationDict = f.getAnnotation(RelationDict.class);
            if (relationDict != null) {
                if (relationDictStructList == null) {
                    relationDictStructList = new LinkedList<>();
                }
                RelationStruct relationStruct = new RelationStruct();
                relationStruct.relationField = f;
                relationStruct.masterIdField = ReflectUtil.getField(modelClass, relationDict.masterIdField());
                relationStruct.relationDict = relationDict;
                if (StringUtils.isNotBlank(relationDict.equalOneToOneRelationField())) {
                    relationStruct.equalOneToOneRelationField =
                            ReflectUtil.getField(modelClass, relationDict.equalOneToOneRelationField());
                }
                relationStruct.service = ApplicationContextHolder.getBean(
                        StringUtils.uncapitalize(relationDict.slaveServiceName()));
                relationDictStructList.add(relationStruct);
                continue;
            }
            RelationOneToOne relationOneToOne = f.getAnnotation(RelationOneToOne.class);
            if (relationOneToOne != null) {
                if (relationOneToOneStructList == null) {
                    relationOneToOneStructList = new LinkedList<>();
                }
                RelationStruct relationStruct = new RelationStruct();
                relationStruct.relationField = f;
                relationStruct.masterIdField = ReflectUtil.getField(modelClass, relationOneToOne.masterIdField());
                relationStruct.relationOneToOne = relationOneToOne;
                relationStruct.service = ApplicationContextHolder.getBean(
                        StringUtils.uncapitalize(relationOneToOne.slaveServiceName()));
                relationOneToOneStructList.add(relationStruct);
                continue;
            }
            RelationOneToManyAggregation relationOneToManyAggregation = f.getAnnotation(RelationOneToManyAggregation.class);
            if (relationOneToManyAggregation != null) {
                if (relationOneToManyAggrStructList == null) {
                    relationOneToManyAggrStructList = new LinkedList<>();
                }
                RelationStruct relationStruct = new RelationStruct();
                relationStruct.relationField = f;
                relationStruct.masterIdField = ReflectUtil.getField(modelClass, relationOneToManyAggregation.masterIdField());
                relationStruct.relationOneToManyAggregation = relationOneToManyAggregation;
                relationStruct.service = ApplicationContextHolder.getBean(
                        StringUtils.uncapitalize(relationOneToManyAggregation.slaveServiceName()));
                relationOneToManyAggrStructList.add(relationStruct);
                continue;
            }
            RelationManyToManyAggregation relationManyToManyAggregation = f.getAnnotation(RelationManyToManyAggregation.class);
            if (relationManyToManyAggregation != null) {
                if (relationManyToManyAggrStructList == null) {
                    relationManyToManyAggrStructList = new LinkedList<>();
                }
                RelationStruct relationStruct = new RelationStruct();
                relationStruct.relationField = f;
                relationStruct.masterIdField = ReflectUtil.getField(modelClass, relationManyToManyAggregation.masterIdField());
                relationStruct.relationManyToManyAggregation = relationManyToManyAggregation;
                relationStruct.service = ApplicationContextHolder.getBean(
                        StringUtils.uncapitalize(relationManyToManyAggregation.slaveServiceName()));
                relationManyToManyAggrStructList.add(relationStruct);
            }
        }
    }

    /**
     * 缺省实现返回null，在进行一对多和多对多聚合计算时，没有额外的自定义过滤条件。如有需要，需子类自行实现。
     *
     * @return 自定义过滤条件列表。
     */
    protected Map<String, List<MyWhereCriteria>> buildAggregationAdditionalWhereCriteria() {
        return null;
    }

    /**
     * 判断当前对象的关联字段数据是否需要被验证，如果原有对象为null，表示新对象第一次插入，则必须验证。
     *
     * @param object         新对象。
     * @param originalObject 原有对象。
     * @param fieldGetter    获取需要验证字段的函数对象。
     * @param <T>            需要验证字段的类型。
     * @return 需要关联验证返回true，否则false。
     */
    protected <T> boolean needToVerify(M object, M originalObject, Function<M, T> fieldGetter) {
        T data = fieldGetter.apply(object);
        if (data == null) {
            return false;
        }
        if (data instanceof String) {
            String stringData = (String) data;
            if (stringData.length() == 0) {
                return false;
            }
        }
        if (originalObject == null) {
            return true;
        }
        T originalData = fieldGetter.apply(originalObject);
        return !data.equals(originalData);
    }

    /**
     * 通过(In-list)条件和orderBy条件，构建Example对象，以供后续的查询操作使用。
     *
     * @param inFilterField  参与(In-list)过滤的Java字段。
     * @param inFilterValues 参与(In-list)过滤的Java字段值集合。
     * @param orderBy        排序字段。
     * @param <T>            in 属性字段的类型。
     * @return 构建后的Example对象。
     */
    protected <T> Example makeDefaultInListExample(String inFilterField, Collection<T> inFilterValues, String orderBy) {
        Set<T> inFilterValueSet;
        Example e = new Example(modelClass);
        if (StringUtils.isNotBlank(orderBy)) {
            e.setOrderByClause(orderBy);
        }
        if (inFilterValues instanceof Set) {
            inFilterValueSet = (Set<T>) inFilterValues;
        } else {
            inFilterValueSet = new HashSet<>(inFilterValues.size());
            inFilterValueSet.addAll(inFilterValues);
        }
        e.createCriteria().andIn(inFilterField, inFilterValueSet);
        return e;
    }

    private void doMakeLocalAggregationData(
            List<Map<String, Object>> aggregationMapList,
            List<? extends M> resultList,
            RelationStruct relationStruct) {
        if (CollectionUtils.isEmpty(resultList)) {
            return;
        }
        // 根据获取的分组聚合结果集，绑定到主表总的关联字段。
        if (CollectionUtils.isNotEmpty(aggregationMapList)) {
            Map<Object, Object> relatedMap = new HashMap<>(aggregationMapList.size());
            for (Map<String, Object> map : aggregationMapList) {
                relatedMap.put(map.get(GROUPED_KEY), map.get(AGGREGATED_VALUE));
            }
            for (M dataObject : resultList) {
                Object masterIdValue = ReflectUtil.getFieldValue(dataObject, relationStruct.masterIdField);
                if (masterIdValue != null) {
                    Object value = relatedMap.get(masterIdValue);
                    if (value != null) {
                        ReflectUtil.setFieldValue(dataObject, relationStruct.relationField, value);
                    }
                }
            }
        }
    }

    private Tuple2<String, String> makeSelectListAndGroupByClause(
            String groupTableName,
            String groupColumnName,
            Class<?> aggregationModel,
            String aggregationTableName,
            String aggregationField,
            Integer aggregationType) {
        if (!AggregationType.isValid(aggregationType)) {
            throw new IllegalArgumentException("Invalid AggregationType Value ["
                    + aggregationType + "] in Model [" + aggregationModel.getName() + "].");
        }
        String aggregationFunc = AggregationType.getAggregationFunction(aggregationType);
        String aggregationColumn = MyModelUtil.mapToColumnName(aggregationField, aggregationModel);
        if (StringUtils.isBlank(aggregationColumn)) {
            throw new IllegalArgumentException("Invalid AggregationField ["
                    + aggregationField + "] in Model [" + aggregationModel.getName() + "].");
        }
        // 构建Select List
        // 如：r_table.master_id groupedKey, SUM(r_table.aggr_column) aggregated_value
        StringBuilder groupedSelectList = new StringBuilder(128);
        groupedSelectList.append(groupTableName)
                .append(".")
                .append(groupColumnName)
                .append(" ")
                .append(GROUPED_KEY)
                .append(", ")
                .append(aggregationFunc)
                .append("(")
                .append(aggregationTableName)
                .append(".")
                .append(aggregationColumn)
                .append(") ")
                .append(AGGREGATED_VALUE)
                .append(" ");
        StringBuilder groupBy = new StringBuilder(64);
        groupBy.append(groupTableName).append(".").append(groupColumnName);
        return new Tuple2<>(groupedSelectList.toString(), groupBy.toString());
    }

    static class RelationStruct {
        public Field relationField;
        public Field masterIdField;
        public Field equalOneToOneRelationField;
        public BaseService<Object, Object> service;
        public Map<Object, String> dictMap;
        public RelationDict relationDict;
        public RelationOneToOne relationOneToOne;
        public RelationConstDict relationConstDict;
        public RelationOneToManyAggregation relationOneToManyAggregation;
        public RelationManyToManyAggregation relationManyToManyAggregation;
    }
}
