package com.minidoodle.service;

import com.minidoodle.config.CacheConfig;
import com.minidoodle.domain.Calendar;
import com.minidoodle.domain.User;
import com.minidoodle.dto.request.CreateUserRequest;
import com.minidoodle.dto.response.UserResponse;
import com.minidoodle.exception.DuplicateResourceException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final DomainMapper mapper;

    @Transactional
    public UserResponse createUser(CreateUserRequest req) {
        String normalizedEmail = req.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("User with email already exists: " + normalizedEmail);
        }

        User user = User.builder()
                .email(normalizedEmail)
                .displayName(req.displayName().trim())
                .build();

        // Lazily create the per-user calendar in the same transaction.
        Calendar calendar = Calendar.builder()
                .user(user)
                .timezone(req.timezone() == null || req.timezone().isBlank() ? "UTC" : req.timezone())
                .build();
        user.setCalendar(calendar);

        User saved = userRepository.save(user);
        log.info("Created user id={} email={}", saved.getId(), saved.getEmail());
        return mapper.toUserResponse(saved);
    }

    @Cacheable(value = CacheConfig.CACHE_USER, key = "#id")
    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        User u = userRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("User", id));
        return mapper.toUserResponse(u);
    }

    @CacheEvict(value = CacheConfig.CACHE_USER, key = "#id")
    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw ResourceNotFoundException.of("User", id);
        }
        userRepository.deleteById(id);
        log.info("Deleted user id={}", id);
    }
}
