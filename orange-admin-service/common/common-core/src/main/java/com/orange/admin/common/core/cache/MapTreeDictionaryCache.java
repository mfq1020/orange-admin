package com.orange.admin.common.core.cache;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import java.util.*;
import java.util.function.Function;

/**
 * 树形字典数据内存缓存对象。
 *
 * @param <K> 字典表主键类型。
 * @param <V> 字典表对象类型。
 * @author Stephen.Liu
 * @date 2020-04-11
 */
public class MapTreeDictionaryCache<K, V> extends MapDictionaryCache<K, V> {

    private final Multimap<K, V> allTreeMap = LinkedHashMultimap.create();
    /**
     * 获取字典父主键数据的函数对象。
     */
    protected Function<V, K> parentIdGetter;

    /**
     * 当前对象的构造器函数。
     *
     * @param idGetter 获取当前类主键字段值的函数对象。
     * @param parentIdGetter 获取当前类父主键字段值的函数对象。
     * @param <K> 字典主键类型。
     * @param <V> 字典对象类型
     * @return 实例化后的树形字典内存缓存对象。
     */
    public static <K, V> MapTreeDictionaryCache<K, V> create(Function<V, K> idGetter, Function<V, K> parentIdGetter) {
        if (idGetter == null) {
            throw new IllegalArgumentException("IdGetter can't be NULL.");
        }
        if (parentIdGetter == null) {
            throw new IllegalArgumentException("ParentIdGetter can't be NULL.");
        }
        return new MapTreeDictionaryCache<>(idGetter, parentIdGetter);
    }

    public MapTreeDictionaryCache(Function<V, K> idGetter, Function<V, K> parentIdGetter) {
        super(idGetter);
        this.parentIdGetter = parentIdGetter;
    }

    /**
     * 获取该父主键的子数据列表。
     *
     * @param parentId 父主键Id。
     * @return 子数据列表。
     */
    public synchronized List<V> getListByParentId(K parentId) {
        return new LinkedList<>(allTreeMap.get(parentId));
    }

    @Override
    public synchronized void putAll(List<V> dataList) {
        if (dataList == null) {
            return;
        }
        super.putAll(dataList);
        dataList.forEach(data -> {
            K parentId = parentIdGetter.apply(data);
            allTreeMap.remove(parentId, data);
            allTreeMap.put(parentId, data);
        });
    }

    @Override
    public synchronized void put(K id, V data) {
        super.put(id, data);
        K parentId = parentIdGetter.apply(data);
        allTreeMap.remove(parentId, data);
        allTreeMap.put(parentId, data);
    }

    @Override
    public synchronized V invalidate(K id) {
        V v = super.invalidate(id);
        if (v != null) {
            K parentId = parentIdGetter.apply(v);
            allTreeMap.remove(parentId, v);
        }
        return v;
    }

    @Override
    public synchronized void invalidateSet(Set<K> keys) {
        keys.forEach(id -> {
            if (id != null) {
                V data = dataMap.remove(id);
                if (data != null) {
                    K parentId = parentIdGetter.apply(data);
                    allTreeMap.remove(parentId, data);
                }
            }
        });
    }

    @Override
    public synchronized void invalidateAll() {
        super.invalidateAll();
        allTreeMap.clear();
    }
}
