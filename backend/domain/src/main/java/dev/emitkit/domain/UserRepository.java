package dev.emitkit.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Users, keyed by ULID. */
public interface UserRepository extends JpaRepository<User, String> {

  Optional<User> findByGithubId(long githubId);
}
