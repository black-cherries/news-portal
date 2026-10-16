package org.newsportal.source;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsSourceRepository extends JpaRepository<NewsSource, Long> {

	Optional<NewsSource> findByCode(String code);

}
