package org.migrax.springdata.fixtures;

import org.springframework.data.repository.ListCrudRepository;

public interface ProductRepository extends ListCrudRepository<Product, String> {}
