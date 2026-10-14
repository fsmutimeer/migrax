package org.acme;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/notes")
public class Notes extends HttpServlet {
  @PersistenceContext EntityManager em;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.getWriter().write(String.join(",",
        em.createQuery("select n.body from Note n order by n.id", String.class).getResultList()));
  }
}
