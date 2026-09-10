package com.example.springexample.Services;

import com.example.springexample.JPA_Entities.User;
import com.example.springexample.Repositories.Auth_rep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UsernameSearchServiceTest {

    private Auth_rep authRep;
    private UsernameSearchService search;

    @BeforeEach
    void setUp() {
        authRep = mock(Auth_rep.class);
        search = new UsernameSearchService(authRep);
    }

    private User user(long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        return u;
    }

    @Test
    void prefixIsLowercasedAndTerminatedWithWildcard() {
        when(authRep.searchByNamePrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(user(2L, "Миша")));

        List<User> found = search.searchByPrefix("МиШ", 1L, 10);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(authRep).searchByNamePrefix(pattern.capture(), eq(1L), any(Pageable.class));
        assertEquals("миш%", pattern.getValue());
        assertEquals(1, found.size());
        assertEquals("Миша", found.get(0).getName());
    }

    @Test
    void requesterIdIsPassedToRepositoryForExclusion() {
        when(authRep.searchByNamePrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        search.searchByPrefix("a", 42L, 10);

        verify(authRep).searchByNamePrefix(anyString(), eq(42L), any(Pageable.class));
    }

    @Test
    void limitIsPassedAsFirstPageOfThatSize() {
        when(authRep.searchByNamePrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        search.searchByPrefix("a", 1L, 10);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(authRep).searchByNamePrefix(anyString(), anyLong(), page.capture());
        assertEquals(0, page.getValue().getPageNumber());
        assertEquals(10, page.getValue().getPageSize());
    }

    @Test
    void likeWildcardsInInputAreEscaped() {
        when(authRep.searchByNamePrefix(anyString(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        search.searchByPrefix("%_!", 1L, 10);

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(authRep).searchByNamePrefix(pattern.capture(), anyLong(), any(Pageable.class));
        assertEquals("!%!_!!%", pattern.getValue());
    }

    @Test
    void blankPrefixReturnsEmptyWithoutTouchingRepository() {
        assertTrue(search.searchByPrefix("", 1L, 10).isEmpty());
        assertTrue(search.searchByPrefix("   ", 1L, 10).isEmpty());
        assertTrue(search.searchByPrefix(null, 1L, 10).isEmpty());

        verify(authRep, never()).searchByNamePrefix(anyString(), anyLong(), any(Pageable.class));
    }

    @Test
    void nonPositiveLimitReturnsEmptyWithoutTouchingRepository() {
        assertTrue(search.searchByPrefix("ми", 1L, 0).isEmpty());
        assertTrue(search.searchByPrefix("ми", 1L, -1).isEmpty());

        verify(authRep, never()).searchByNamePrefix(anyString(), anyLong(), any(Pageable.class));
    }
}
