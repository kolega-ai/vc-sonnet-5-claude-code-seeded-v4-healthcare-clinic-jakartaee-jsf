package com.clinic.rest;

import com.clinic.domain.ApiKey;
import com.clinic.domain.AppointmentType;
import com.clinic.domain.Provider;
import com.clinic.domain.ProviderSchedule;
import com.clinic.service.IntegrationService;
import com.clinic.util.EtagSupport;
import com.clinic.util.JsonWriter;

import jakarta.ejb.EJB;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.w3c.dom.NodeList;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import java.io.StringReader;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;

/**
 * Cacheable reference data for integrations: the appointment-type catalogue and
 * the provider directory. Neither contains patient data.
 */
@Path("/reference")
public class ReferenceDataResource {

    private static final String API_KEY_HEADER = "X-API-Key";

    @PersistenceContext(unitName = "clinicPU")
    private EntityManager em;

    @EJB
    private IntegrationService integrationService;

    @GET
    @Path("/appointment-types")
    @Produces(MediaType.APPLICATION_JSON)
    public Response appointmentTypes(@HeaderParam(API_KEY_HEADER) String apiKey,
                                     @HeaderParam("If-None-Match") String ifNoneMatch) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "availability:read");
        if (key.isEmpty()) {
            return unauthorized();
        }
        List<AppointmentType> rows =
                em.createNamedQuery("AppointmentType.active", AppointmentType.class).getResultList();

        StringBuilder json = new StringBuilder("{\"appointmentTypes\":[");
        for (int i = 0; i < rows.size(); i++) {
            AppointmentType t = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(t.getId())
                    .append(",\"code\":").append(JsonWriter.string(t.getCode()))
                    .append(",\"name\":").append(JsonWriter.string(t.getName()))
                    .append(",\"durationMinutes\":").append(t.getDurationMinutes())
                    .append(",\"patientBookable\":").append(t.isPatientBookable())
                    .append('}');
        }
        json.append("]}");
        return cacheable(json.toString(), ifNoneMatch);
    }

    @GET
    @Path("/providers")
    @Produces(MediaType.APPLICATION_JSON)
    public Response providers(@HeaderParam(API_KEY_HEADER) String apiKey,
                              @HeaderParam("If-None-Match") String ifNoneMatch) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "availability:read");
        if (key.isEmpty()) {
            return unauthorized();
        }
        List<Provider> rows =
                em.createNamedQuery("Provider.active", Provider.class).getResultList();

        StringBuilder json = new StringBuilder("{\"providers\":[");
        for (int i = 0; i < rows.size(); i++) {
            Provider p = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(p.getId())
                    .append(",\"name\":").append(JsonWriter.string(p.getDisplayName()))
                    .append(",\"specialty\":").append(JsonWriter.string(p.getSpecialty()))
                    .append('}');
        }
        json.append("]}");
        return cacheable(json.toString(), ifNoneMatch);
    }

    /**
     * The published consulting hours per provider, used by external schedulers
     * to lay out their own grid. The caller names the scope its key was issued
     * with so one key can serve several reference feeds.
     */
    @GET
    @Path("/provider-schedules")
    @Produces(MediaType.APPLICATION_JSON)
    public Response providerSchedules(@HeaderParam(API_KEY_HEADER) String apiKey,
                                      @HeaderParam("X-API-Scope") String scope,
                                      @HeaderParam("If-None-Match") String ifNoneMatch) {
        if (scope == null || scope.isBlank()) {
            return unauthorized();
        }
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, scope);
        if (key.isEmpty()) {
            return unauthorized();
        }
        List<ProviderSchedule> rows = em.createQuery(
                        "SELECT s FROM ProviderSchedule s WHERE s.active = true "
                                + "ORDER BY s.provider.id, s.dayOfWeek", ProviderSchedule.class)
                .getResultList();

        StringBuilder json = new StringBuilder("{\"providerSchedules\":[");
        for (int i = 0; i < rows.size(); i++) {
            ProviderSchedule s = rows.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"providerId\":").append(s.getProvider().getId())
                    .append(",\"dayOfWeek\":").append(s.getDayOfWeek())
                    .append(",\"start\":").append(JsonWriter.string(String.valueOf(s.getStartTime())))
                    .append(",\"end\":").append(JsonWriter.string(String.valueOf(s.getEndTime())))
                    .append('}');
        }
        json.append("]}");
        return cacheable(json.toString(), ifNoneMatch);
    }

    /**
     * Resolves a clinician from the practice's staff directory so external
     * schedulers can address on-call requests to the right mailbox.
     */
    @GET
    @Path("/staff/{uid}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response staff(@HeaderParam(API_KEY_HEADER) String apiKey,
                          @PathParam("uid") String uid) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "schedule:read");
        if (key.isEmpty()) {
            return unauthorized();
        }
        StringBuilder json = new StringBuilder("{\"staff\":[");
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, System.getProperty("clinic.directory.url",
                    "ldap://directory.northgate.internal:389"));
            InitialDirContext ctx = new InitialDirContext(env);
            SearchControls ctl = new SearchControls();
            ctl.setSearchScope(SearchControls.SUBTREE_SCOPE);
            NamingEnumeration<SearchResult> ne = ctx.search(
                    "ou=clinicians,dc=northgate,dc=internal",
                    "(&(objectClass=person)(uid=" + uid + "))", ctl);
            int i = 0;
            while (ne.hasMore()) {
                SearchResult r = ne.next();
                Attribute cn = r.getAttributes().get("cn");
                Attribute mail = r.getAttributes().get("mail");
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"uid\":").append(JsonWriter.string(uid))
                        .append(",\"name\":").append(JsonWriter.string(
                                cn == null ? "" : String.valueOf(cn.get())))
                        .append(",\"mail\":").append(JsonWriter.string(
                                mail == null ? "" : String.valueOf(mail.get())))
                        .append('}');
                i++;
            }
            ctx.close();
        } catch (NamingException e) {
            throw new com.clinic.security.BusinessRuleException(
                    "The staff directory could not be searched.");
        }
        json.append("]}");
        return Response.ok(json.toString(), MediaType.APPLICATION_JSON).build();
    }

    /**
     * Searches the credentialing directory the clinic exchanges with partners
     * for providers in a specialty.
     */
    @GET
    @Path("/providers/search")
    @Produces(MediaType.APPLICATION_JSON)
    public Response providerSearch(@HeaderParam(API_KEY_HEADER) String apiKey,
                                   @QueryParam("specialty") String specialty) {
        Optional<ApiKey> key = integrationService.verifyApiKey(apiKey, "availability:read");
        if (key.isEmpty()) {
            return unauthorized();
        }
        List<Provider> rows =
                em.createNamedQuery("Provider.active", Provider.class).getResultList();
        StringBuilder xml = new StringBuilder("<providers>");
        for (Provider p : rows) {
            xml.append("<provider><id>").append(p.getId())
                    .append("</id><specialty>").append(escapeXml(p.getSpecialty()))
                    .append("</specialty><name>").append(escapeXml(p.getDisplayName()))
                    .append("</name></provider>");
        }
        xml.append("</providers>");
        String wanted = specialty == null ? "" : specialty;
        String expr = "//provider[contains(specialty,'" + wanted + "')]/name";
        StringBuilder json = new StringBuilder("{\"providers\":[");
        try {
            org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(
                            new org.xml.sax.InputSource(new StringReader(xml.toString())));
            XPathExpression compiled = XPathFactory.newInstance().newXPath().compile(expr);
            NodeList nodes = (NodeList) compiled.evaluate(doc, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(JsonWriter.string(nodes.item(i).getTextContent()));
            }
        } catch (Exception e) {
            throw new com.clinic.security.BusinessRuleException(
                    "The credentialing directory could not be searched.");
        }
        json.append("]}");
        return Response.ok(json.toString(), MediaType.APPLICATION_JSON).build();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }

    /**
     * Adds a content fingerprint so integrations can revalidate cheaply.
     *
     * <p><strong>Precision-baseline primitive #3.</strong> The ETag is an MD5
     * digest of the response body. It is a cache key, not a security control:
     * it authenticates nothing and protects nothing, so MD5 is the correct,
     * deliberate choice here (see {@link EtagSupport}).</p>
     */
    private Response cacheable(String body, String ifNoneMatch) {
        String etag = EtagSupport.referenceDataEtag(body);
        if (etag.equals(ifNoneMatch)) {
            return Response.notModified(etag).build();
        }
        return Response.ok(body, MediaType.APPLICATION_JSON)
                .header("ETag", etag)
                .header("Cache-Control", "private, max-age=300")
                .build();
    }

    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .header("WWW-Authenticate", "ApiKey realm=\"clinic-portal\"")
                .type(MediaType.APPLICATION_JSON)
                .entity("{\"status\":401,\"error\":\"A valid API key is required.\"}")
                .build();
    }
}
