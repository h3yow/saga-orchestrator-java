package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.participant.FlightParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.HotelParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.LoyaltyParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.VisaParticipant;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构造“差旅预订”业务的 Saga 定义（四步流程，参与方组合与下单完全不同）：
 * <pre>
 *   1. flight.bookFlight      （预订机票） 补偿 cancelFlight
 *   2. hotel.bookHotel        （预订酒店） 补偿 cancelHotel
 *   3. visa.applyVisa         （办理签证） 补偿 withdrawVisa
 *   4. loyalty.awardPoints    （累计积分） 补偿 revokePoints
 * </pre>
 */
@Component
public class TravelSagaFactory implements SagaDefinitionFactory {

    public static final String BUSINESS_TYPE = "TRAVEL_BOOKING";

    private final FlightParticipant flight;
    private final HotelParticipant hotel;
    private final VisaParticipant visa;
    private final LoyaltyParticipant loyalty;

    public TravelSagaFactory(FlightParticipant flight,
                             HotelParticipant hotel,
                             VisaParticipant visa,
                             LoyaltyParticipant loyalty) {
        this.flight = flight;
        this.hotel = hotel;
        this.visa = visa;
        this.loyalty = loyalty;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public SagaDefinition create() {
        return new SagaDefinition(BUSINESS_TYPE, List.of(
                new SagaStep("book-flight", flight, "bookFlight", "cancelFlight"),
                new SagaStep("book-hotel", hotel, "bookHotel", "cancelHotel"),
                new SagaStep("apply-visa", visa, "applyVisa", "withdrawVisa"),
                new SagaStep("award-loyalty", loyalty, "awardPoints", "revokePoints")
        ));
    }
}
