package org.acme;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;

@Path("/notes")
public class Notes {
  @Inject EntityManager em;

  @GET
  public String list() {
    List<String> bodies = em.createQuery("select n.body from Note n order by n.id", String.class)
        .getResultList();
    return String.join(",", bodies);
  }
}
