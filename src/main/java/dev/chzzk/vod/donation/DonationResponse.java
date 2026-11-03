package dev.chzzk.vod.donation;

import java.time.Instant;

import dev.chzzk.vod.user.UserSummary;

public record DonationResponse(String orderId, long amount, String message, DonationStatus status,
		UserSummary donor, UserSummary recipient, DonationTarget targetType, String targetId, String method,
		String failReason, Instant createdAt, Instant paidAt) {

	public static DonationResponse from(Donation d) {
		return new DonationResponse(d.getOrderId(), d.getAmount(), d.getMessage(), d.getStatus(),
				UserSummary.of(d.getDonor()), UserSummary.of(d.getRecipient()), d.getTargetType(), d.getTargetId(),
				d.getMethod(), d.getFailReason(), d.getCreatedAt(), d.getPaidAt());
	}

}
