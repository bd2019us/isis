/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.isis.core.runtime.services.bookmarks;

import static org.apache.isis.commons.internal.base._With.acceptIfPresent;
import static org.apache.isis.commons.internal.base._With.mapIfPresentElse;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.isis.applib.services.bookmark.Bookmark;
import org.apache.isis.applib.services.bookmark.BookmarkHolder;
import org.apache.isis.applib.services.bookmark.BookmarkService;
import org.apache.isis.applib.services.registry.ServiceRegistry;
import org.apache.isis.applib.services.wrapper.WrapperFactory;
import org.apache.isis.applib.tree.TreeState;
import org.apache.isis.commons.internal.base._Casts;
import org.apache.isis.commons.internal.collections._Lists;
import org.apache.isis.commons.internal.collections._Maps;
import org.apache.isis.commons.internal.collections._Sets;
import org.apache.isis.commons.internal.memento._Mementos.SerializingAdapter;
import org.apache.isis.core.metamodel.adapter.ObjectAdapter;
import org.apache.isis.core.metamodel.adapter.concurrency.ConcurrencyChecking;
import org.apache.isis.core.metamodel.adapter.oid.Oid;
import org.apache.isis.core.metamodel.adapter.oid.Oid.Factory;
import org.apache.isis.core.metamodel.adapter.oid.RootOid;
import org.apache.isis.core.metamodel.exceptions.persistence.ObjectNotFoundException;
import org.apache.isis.core.metamodel.services.persistsession.ObjectAdapterService;
import org.apache.isis.core.metamodel.spec.ObjectSpecification;
import org.apache.isis.core.metamodel.specloader.SpecificationLoader;
import org.apache.isis.core.runtime.system.context.IsisContext;
import org.apache.isis.core.runtime.system.persistence.PersistenceSession;

/**
 * This service enables a serializable 'bookmark' to be created for an entity.
 *
 * <p>
 * This implementation has no UI and there are no other implementations of the service API, and so it annotated
 * with {@link org.apache.isis.applib.annotation.DomainService}.  Because this class is implemented in core, this means
 * that it is automatically registered and available for use; no further configuration is required.
 */
@Singleton @Priority(value=10)
public class BookmarkServiceInternalDefault implements BookmarkService, SerializingAdapter {


    @Override
    public Object lookup(
            final BookmarkHolder bookmarkHolder,
            final FieldResetPolicy fieldResetPolicy) {
        Bookmark bookmark = bookmarkHolder.bookmark();
        return bookmark != null? lookup(bookmark, fieldResetPolicy): null;
    }

    private Object lookupInternal(
            final Bookmark bookmark,
            final FieldResetPolicy fieldResetPolicy) {
        if(bookmark == null) {
            return null;
        }
        try {
            return _lookup(bookmark, fieldResetPolicy);
        } catch(ObjectNotFoundException ex) {
            return null;
        }
    }

    @Override
    public Object lookup(
            final Bookmark bookmark,
            final FieldResetPolicy fieldResetPolicy) {
        if(bookmark == null) {
            return null;
        }
        final String objectType = bookmark.getObjectType();
        final Object service = lookupService(objectType);
        if(service != null) {
            return service;
        }
        return lookupInternal(bookmark, fieldResetPolicy);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T lookup(
            final Bookmark bookmark,
            final FieldResetPolicy fieldResetPolicy,
            Class<T> cls) {
        return (T) lookup(bookmark, fieldResetPolicy);
    }

    
    @Override
    public Bookmark bookmarkFor(final Object domainObject) {
        if(domainObject == null) {
            return null;
        }
        final ObjectAdapter adapter = objectAdapterProvider.adapterFor(unwrapped(domainObject));
        if(adapter.isValue()) {
            // values cannot be bookmarked
            return null;
        }
        final Oid oid = adapter.getOid();
        if(!(oid instanceof RootOid)) {
            // must be root
            return null;
        }
        final RootOid rootOid = (RootOid) oid;
        return rootOid.asBookmark();
    }

    private Object unwrapped(Object domainObject) {
        return wrapperFactory != null ? wrapperFactory.unwrap(domainObject) : domainObject;
    }

    @Override
    public Bookmark bookmarkFor(Class<?> cls, String identifier) {
        final ObjectSpecification objectSpec = specificationLoader.loadSpecification(cls);
        String objectType = objectSpec.getSpecId().asString();
        return new Bookmark(objectType, identifier);
    }

    private Map<String,Object> servicesByClassName;
    private Object lookupService(final String className) {
        cacheServicesByClassNameIfNecessary();
        return servicesByClassName.get(className);
    }

    private void cacheServicesByClassNameIfNecessary() {
        if (servicesByClassName == null) {
            final Map<String,Object> servicesByClassName = _Maps.newHashMap();
            final Stream<Object> registeredServices = serviceRegistry.streamServices();
            registeredServices.forEach(registeredService->{
                final String serviceClassName = registeredService.getClass().getName();
                servicesByClassName.put(serviceClassName, registeredService);
            });
            this.servicesByClassName = servicesByClassName;
        }
    }

    // -- SERIALIZING ADAPTER IMPLEMENTATION

    @Override
    public <T> T read(Class<T> cls, Serializable value) {

        if(Bookmark.class.equals(cls)) {
            return _Casts.uncheckedCast(value);
        }

        if(Bookmark.class.isAssignableFrom(value.getClass())) {
            final Bookmark valueBookmark = (Bookmark) value;
            return _Casts.uncheckedCast(lookup(valueBookmark, FieldResetPolicy.RESET));
        }

        return _Casts.uncheckedCast(value);
    }

    @Override
    public Serializable write(Object value) {
        if(isPredefinedSerializable(value.getClass())) {
            return (Serializable) value;
        } else {
            final Bookmark valueBookmark = bookmarkFor(value);
            return valueBookmark;
        }
    }

    // -- HELPER

    private final static Set<Class<? extends Serializable>> serializableFinalTypes = _Sets.of(
            String.class, String[].class,
            Class.class, Class[].class,
            Boolean.class, boolean.class, Boolean[].class, boolean[].class,
            Byte.class, byte.class, Byte[].class, byte[].class,
            Short.class, short.class, Short[].class, short[].class,
            Integer.class, int.class, Integer[].class, int[].class,
            Long.class, long.class, Long[].class, long[].class,
            Float.class, float.class, Float[].class, float[].class,
            Double.class, double.class, Double[].class, double[].class
            );

    private final static List<Class<? extends Serializable>> serializableTypes = _Lists.of(
            BigDecimal.class,
            BigInteger.class,
            java.util.Date.class,
            java.sql.Date.class,
            Enum.class,
            Bookmark.class,
            TreeState.class
            );

    private static boolean isPredefinedSerializable(final Class<?> cls) {
        if(!Serializable.class.isAssignableFrom(cls)) {
            return false;
        }
        //[ahuber] any non-scalar values could be problematic, so we are careful with wild-cards here
        if(cls.getName().startsWith("java.time.")) {
            return true;
        }
        if(cls.getName().startsWith("org.joda.time.")) {
            return true;
        }
        if(serializableFinalTypes.contains(cls)) {
            return true;
        }
        return serializableTypes.stream().anyMatch(t->t.isAssignableFrom(cls));
    }
    
    /**
     * Provided by <tt>PersistenceSession</tt> when used by framework.
     *
     * <p>
     * Called by <tt>BookmarkServicesDefault</tt>.
     * @return
     */
    private Object _lookup(
            final Bookmark bookmark,
            final BookmarkService.FieldResetPolicy fieldResetPolicy) {
        
        final RootOid rootOid = Factory.ofBookmark(bookmark);
        final PersistenceSession ps = IsisContext.getPersistenceSession().get();
        final boolean denyRefresh = fieldResetPolicy == BookmarkService.FieldResetPolicy.DONT_REFRESH; 
                        
        if(rootOid.isViewModel()) {
            final ObjectAdapter adapter = ps.adapterFor(rootOid);
            final Object pojo = mapIfPresentElse(adapter, ObjectAdapter::getPojo, null);
            
            return pojo;
            
        } else if(denyRefresh) {
            
            final Object pojo = ps.fetchPersistentPojoInTransaction(rootOid);
            return pojo;            
            
        } else {
            final ObjectAdapter adapter = ps.adapterFor(rootOid);
            
            final Object pojo = mapIfPresentElse(adapter, ObjectAdapter::getPojo, null);
            acceptIfPresent(pojo, ps::refreshRootInTransaction);
            return pojo;
        }
        
    }


    // -- INJECTION

    @Inject SpecificationLoader specificationLoader;
    @Inject WrapperFactory wrapperFactory;
    @Inject ServiceRegistry serviceRegistry;
    @Inject ObjectAdapterService objectAdapterProvider;

}
