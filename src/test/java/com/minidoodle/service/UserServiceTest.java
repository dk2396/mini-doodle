package com.minidoodle.service;

import com.minidoodle.domain.User;
import com.minidoodle.dto.request.CreateUserRequest;
import com.minidoodle.dto.response.UserResponse;
import com.minidoodle.exception.DuplicateResourceException;
import com.minidoodle.exception.ResourceNotFoundException;
import com.minidoodle.mapper.DomainMapper;
import com.minidoodle.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock DomainMapper mapper;

    @InjectMocks UserService service;

    @Test
    void createsUserWithNormalizedEmail() {
        when(userRepository.existsByEmail("alice@x.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        when(mapper.toUserResponse(any(User.class))).thenAnswer(inv ->
                new UserResponse(42L, "alice@x.com", "Alice", "UTC", Instant.now()));

        UserResponse resp = service.createUser(new CreateUserRequest("  Alice@X.COM ", "Alice", null));

        assertThat(resp.id()).isEqualTo(42L);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("alice@x.com");
        assertThat(captor.getValue().getCalendar()).isNotNull();
        assertThat(captor.getValue().getCalendar().getTimezone()).isEqualTo("UTC");
    }

    @Test
    void rejectsDuplicateEmail() {
        when(userRepository.existsByEmail("alice@x.com")).thenReturn(true);
        assertThatThrownBy(() -> service.createUser(new CreateUserRequest("alice@x.com", "Alice", null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void getUserNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getUser(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
