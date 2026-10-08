package io.migrax.helidon.app;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class MemoService {
  @PersistenceContext(unitName = "notes")
  EntityManager em;

  @Transactional
  public long addAndCount(String text) {
    Memo memo = new Memo();
    memo.memoText = text;
    em.persist(memo);
    em.flush();
    return em.createQuery("select count(m) from Memo m", Long.class).getSingleResult();
  }
}
