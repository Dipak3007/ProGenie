package com.progenie.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.progenie.shared.util.QueryUtil;

/**
 * Spring Data repositories read their named queries from the same sql_scripts.properties as {@link QueryUtil}, so
 * {@code @Query(name = QueryConstants.X)} on a repository method uses the JPQL stored under that key.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.progenie", namedQueriesLocation = "classpath:" + QueryUtil.FILE)
public class JpaConfig {
}
