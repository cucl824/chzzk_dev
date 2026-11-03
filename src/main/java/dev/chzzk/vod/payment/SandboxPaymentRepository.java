package dev.chzzk.vod.payment;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SandboxPaymentRepository extends JpaRepository<SandboxPayment, String> {

	Optional<SandboxPayment> findByOrderId(String orderId);

}
