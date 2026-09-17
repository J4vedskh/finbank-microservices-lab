package com.banking.payment.repository;

import com.banking.payment.entity.PaymentOutboxDeadLetterHandoff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PaymentOutboxDeadLetterHandoffRepository
        extends JpaRepository<PaymentOutboxDeadLetterHandoff, Long> {

    @Query("""
            select handoff
            from PaymentOutboxDeadLetterHandoff handoff
            join fetch handoff.outboxEvent
            where handoff.outboxEvent.id = :eventId
            order by handoff.exhaustionSequence asc
            """)
    List<PaymentOutboxDeadLetterHandoff> findByOutboxEventId(
            @Param("eventId") Long eventId
    );

    @Modifying
    @Query("""
            delete from PaymentOutboxDeadLetterHandoff handoff
            where handoff.outboxEvent.id in :eventIds
            """)
    int deleteByOutboxEventIdIn(@Param("eventIds") Collection<Long> eventIds);
}
