/**
 * Copyright (C) 2012 KRM Associates, Inc. healtheme@krminc.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.krminc.phr.api.vitals.service;

import com.krminc.phr.api.service.Api;
import com.krminc.phr.api.vitals.converter.BloodPressureConverter;
import com.krminc.phr.api.vitals.converter.BloodPressuresConverter;
import com.krminc.phr.dao.PersistenceService;
import com.krminc.phr.domain.vitals.BloodPressure;
import com.sun.jersey.api.core.ResourceContext;
import java.util.Collection;
import javax.persistence.EntityManager;
import javax.ws.rs.Path;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.Consumes;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.core.MediaType;
import oracle.toplink.essentials.config.CascadePolicy;
import oracle.toplink.essentials.config.HintValues;
import oracle.toplink.essentials.config.TopLinkQueryHints;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.http.HttpServletRequest;


/**
 * Blood Sugars API RESTful resource class and mapping.
 * 
 * @author cmccall
 */
public class BloodPressuresResource {

    final Logger logger = LoggerFactory.getLogger(BloodPressuresResource.class);

    @Context
    protected UriInfo uriInfo;
    @Context
    protected ResourceContext resourceContext;
    @Context
    protected transient HttpServletRequest servletRequest;

    protected Long healthRecordId;


    public BloodPressuresResource() {
    }

    public void setHealthRecordId(Long healthRecordId) {
        this.healthRecordId = healthRecordId;
    }
    
    /**
     * Get method for retrieving a collection of BloodPressure instance in XML format.
     *
     * @return an instance of BloodPressuresConverter
     */
    @GET
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public BloodPressuresConverter get(
            @QueryParam("start") @DefaultValue("0") int start,
            @QueryParam("max") @DefaultValue("10") int max,
            @QueryParam("source") @DefaultValue("self") String source,
            @QueryParam("orderBy") @DefaultValue("dateadded") String orderBy,
            @QueryParam("desc") @DefaultValue("1") int desc)
    {
        PersistenceService persistenceSvc = PersistenceService.getInstance();
        try {
            persistenceSvc.beginTx();
            return new BloodPressuresConverter(
                getEntities(start, max, source, orderBy, desc),
                uriInfo.getAbsolutePath(),
                Api.DEFAULT_EXPAND_LEVEL
            );
        } finally {
            persistenceSvc.commitTx();
            persistenceSvc.close();
        }
    }

    /**
     * Post method for creating an instance of BloodPressure using XML as the input format.
     *
     * @param data an BloodPressureConverter entity that is deserialized from an XML stream
     * @return an instance of BloodPressureConverter
     */
    @POST
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML})
    public Response post(BloodPressureConverter data) {
        //check healthrecord is genuine
        try {
            Long session = new Long(servletRequest.getSession().getAttribute("healthRecordId").toString());
            if (session!=null) {
                data.setHealthRecordId(session);
            } else {
                throw new WebApplicationException(Response.Status.FORBIDDEN);
            }
        }
        catch(NullPointerException ex) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        
        PersistenceService persistenceSvc = PersistenceService.getInstance();
        try {

            if (data.hasError) {
                throw new WebApplicationException(Response.Status.PRECONDITION_FAILED);
            }

            persistenceSvc.beginTx();
            EntityManager em = persistenceSvc.getEntityManager();
            BloodPressure entity = data.resolveEntity(em);
            createEntity(data.resolveEntity(em));
            persistenceSvc.commitTx();
            return Response.created(uriInfo.getAbsolutePath().resolve(entity.getBloodPressureId() + "/")).build();
        } finally {
            persistenceSvc.close();
        }
    }

    /**
     * Returns a dynamic instance of BloodPressureResource used for entity navigation.
     *
     * @param id bloodPressureId
     * @return an instance of BloodPressureResource
     */
    @Path("{bloodPressureId: \\d+}/")
    public BloodPressureResource getBloodPressureResource(@PathParam("bloodPressureId") Long id) {
        BloodPressureResource resource = resourceContext.getResource(BloodPressureResource.class);
        resource.setId(id);
        return resource;
    }

    @Path("count/")
    @GET
    @Produces({MediaType.APPLICATION_JSON})
    public JSONObject getCount(
            @QueryParam("source")
            @DefaultValue("self")
            String source){
        EntityManager em = PersistenceService.getInstance().getEntityManager();
        Long result = 0L;
        
        result = (Long) em.createNamedQuery("BloodPressure.countByHealthRecordId")
            .setParameter("healthRecordId" , this.healthRecordId)
            .getSingleResult();

        JSONObject jsonResult = new JSONObject();
        try {
            jsonResult.put("count", result);
        }
        catch (JSONException ex) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        return jsonResult;
    }

    /**
     * Returns all the entities associated with this resource.
     *
     * @return a collection of BloodPressure instances
     */
    protected Collection<BloodPressure> getEntities(int start, int max, String source, String orderBy, int desc) {
        EntityManager em = PersistenceService.getInstance().getEntityManager();
        //build query based on source.
        //  orderBy :
        //    date 
        //    addeddate (default)
        //  desc:
        //    0 -- ascending (default)
        //    >0 -- descending
        if(start<0) start=0;
        if(max<1) max=1;
        if(max>100) max=100;

        StringBuilder query = new StringBuilder("SELECT e FROM BloodPressure e WHERE e.healthRecordId = :healthRecordId");

        if(orderBy.equalsIgnoreCase("observeddate")){
            query.append(" ORDER BY e.observedDate");
        } else if(orderBy.equalsIgnoreCase("value")){
            query.append(" ORDER BY e.systolic");
        } else if(orderBy.equalsIgnoreCase("diastolic")){
            query.append(" ORDER BY e.diastolic");
        } else {
            query.append(" ORDER BY e.dateAdded");
        }

        if (desc>0) {
            query.append(" DESC");
        } else {
            query.append(" ASC");
        }

        return em.createQuery(query.toString())
            .setParameter("healthRecordId", healthRecordId)
            .setHint(TopLinkQueryHints.REFRESH, HintValues.TRUE)
            .setHint(TopLinkQueryHints.REFRESH_CASCADE, CascadePolicy.CascadeAllParts)
            .setFirstResult(start)
            .setMaxResults(max)
            .getResultList();
    }

    /**
     * Persist the given entity.
     *
     * @param entity the entity to persist
     */
    protected void createEntity(BloodPressure entity) {
        entity.setBloodPressureId(null);
        EntityManager em = PersistenceService.getInstance().getEntityManager();
        em.persist(entity);
    }
}
