package com.example.springexample.Services;

import com.example.grpc.AuthTransferServiceGrpc;
import com.example.grpc.DataTransferService;
import com.example.springexample.Metrics.GrpcRequestsMetric;
import com.example.springexample.Utils.AuthResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты AuthGrpc (beads 93e): не-200 статус от AuthService должен
 * пробрасываться типизированным AuthResponseException со статусом и сообщением,
 * а не молча схлопываться в null.
 */
@ExtendWith(MockitoExtension.class)
class AuthGrpcTest {

    @Mock
    private GrpcRequestsMetric grpcRequestsMetric;

    @Mock
    private AuthTransferServiceGrpc.AuthTransferServiceBlockingStub authTransferServiceBlockingStub;

    @InjectMocks
    private AuthGrpc authGrpc;

    private DataTransferService.UserDataRequest userData() {
        return DataTransferService.UserDataRequest.newBuilder()
                .setUsername("bob").setPassword("secret").build();
    }

    @Test
    void authRegisterThrowsAuthResponseExceptionOnDuplicateUser() {
        DataTransferService.AuthResponse response = DataTransferService.AuthResponse.newBuilder()
                .setStatus("666")
                .setMessage("User with such name already exists")
                .build();
        when(authTransferServiceBlockingStub.register(any())).thenReturn(response);

        assertThatThrownBy(() -> authGrpc.authRegister(userData()))
                .isInstanceOf(AuthResponseException.class)
                .hasMessage("User with such name already exists")
                .extracting(e -> ((AuthResponseException) e).getStatus())
                .isEqualTo("666");
    }

    @Test
    void authRegisterReturnsResponseOnSuccess() {
        DataTransferService.AuthResponse response = DataTransferService.AuthResponse.newBuilder()
                .setStatus("200").setSub("42").setRole("USER").build();
        when(authTransferServiceBlockingStub.register(any())).thenReturn(response);

        DataTransferService.AuthResponse result = authGrpc.authRegister(userData());

        assertThat(result).isSameAs(response);
    }

    @Test
    void authLoginThrowsAuthResponseExceptionWhenUserMissing() {
        DataTransferService.AuthResponse response = DataTransferService.AuthResponse.newBuilder()
                .setStatus("404")
                .setMessage("User does not exists")
                .build();
        when(authTransferServiceBlockingStub.login(any())).thenReturn(response);

        assertThatThrownBy(() -> authGrpc.authlogin(userData()))
                .isInstanceOf(AuthResponseException.class)
                .hasMessage("User does not exists")
                .extracting(e -> ((AuthResponseException) e).getStatus())
                .isEqualTo("404");
    }

    @Test
    void authLoginReturnsResponseOnSuccess() {
        DataTransferService.AuthResponse response = DataTransferService.AuthResponse.newBuilder()
                .setStatus("200").setSub("42").setRole("USER").build();
        when(authTransferServiceBlockingStub.login(any())).thenReturn(response);

        DataTransferService.AuthResponse result = authGrpc.authlogin(userData());

        assertThat(result).isSameAs(response);
    }
}
