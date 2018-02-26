package io.bisq.engine.app.api;


import io.bisq.common.UserThread;
import io.bisq.common.util.Utilities;
import io.bisq.core.btc.AddressEntry;
import io.bisq.core.trade.BuyerTrade;
import io.bisq.core.trade.Contract;
import io.bisq.core.trade.SellerTrade;
import io.bisq.core.trade.Trade;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.toList;
import static org.springframework.util.MimeTypeUtils.*;

@RestController
@RequestMapping("/api/trade")
@Api(tags = {"Trade"})
public class TradeApi  extends ApiData{

    public static class TradeJson{
        public String id;
        public Date date;
        public Boolean isMyOffer;
        public String type;
        public String state;
        public String phase;
        public JSONObject contract;

    }

    private TradeJson Map(Trade trade) throws ParseException {
        TradeJson tr = new TradeJson();
        Contract co = trade.getContract();


        tr.id = trade.getId();
        tr.date = trade.getTakeOfferDate();
        tr.isMyOffer = trade.getOffer().isMyOffer(keyRing);
        tr.type = trade instanceof BuyerTrade?"BuyerTrade":"SellerTrade";
        tr.state = trade.getState().toString();
        tr.phase = trade.getState().getPhase().toString();
        tr.contract = (JSONObject) new JSONParser().parse(tradeManager.getTradeById(trade.getId()).get().getContractAsJson());

        return tr;
    }

    @RequestMapping(value = "/list", method= RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get a list of all trades")
    public List<TradeJson> listTrades() throws Exception {
        checkErrors();

        return tradeManager.getTradableList().stream().map((trade)->{
            try {
                return Map(trade);
            } catch (ParseException e) {
                e.printStackTrace();
                return new TradeJson();
            }
        }).collect(toList());
    }

    private Message isTrade(String tradeId){
        Message message = new Message();
        if(!tradeManager.getTradeById(tradeId).isPresent()){
            message.success = false;
            message.message = "Could not find trade with id: "+tradeId;
            return message;
        }
        message.success = true;
        return message;
    }

    @RequestMapping(value = "/contract", method= RequestMethod.GET, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Get the contract for a trade")
    public Message getTrade(
            @RequestParam(value = "The trade offer id", required=true)
            String tradeId
    ) throws Exception {
        checkErrors();

        Message message = isTrade(tradeId);
        if(message.success.equals(false)) return message;

        message.message = "Found trade with id: "+tradeId;

        JSONObject json = (JSONObject) new JSONParser().parse(tradeManager.getTradeById(tradeId).get().getContractAsJson());
        message.data = json;
        return message;
    }



    @RequestMapping(value = "/payment/started", method= RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Indicate that payment has started on a trade")
    public Message paymentStarted(
            @RequestParam(value = "The trade offer id", required=true)
            String tradeId
    ) throws Exception {
        checkErrors();

        Message message = isTrade(tradeId);
        if(message.success.equals(false)) return message;

        Trade trade = tradeManager.getTradeById(tradeId).get();

        if(trade instanceof BuyerTrade == false){
            message.success = false;
            message.message = "Not a buyer trade";
            return message;
        }
        if(!trade.getState().getPhase().toString().matches("DEPOSIT_CONFIRMED")){
            message.success = false;
            message.message = "Incorrect phase: "+trade.getState().getPhase();
            return message;
        }

        CompletableFuture<Message> promise = new CompletableFuture<>();
        UserThread.execute(()-> {
            ((BuyerTrade) trade).onFiatPaymentStarted(() -> {
                message.success = true;
                message.message = "Start of payment confirmed";
                promise.complete(message);
            }, (err) -> {
                message.success = false;
                message.message = err;
                promise.complete(message);
            });
        });

        return promise.get();

    }

    @RequestMapping(value = "/payment/received", method= RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Indicate that payment for trade was received")
    public Message paymentReceived(
            @RequestParam(value = "The trade offer id", required=true)
                    String tradeId
    ) throws Exception {
        checkErrors();

        Message message = isTrade(tradeId);
        if(message.success.equals(false)) return message;

        Trade trade = tradeManager.getTradeById(tradeId).get();

        if(trade instanceof SellerTrade == false){
            message.success = false;
            message.message = "Not a seller trade";
            return message;
        }
        if(!trade.getState().getPhase().toString().matches("FIAT_SENT")){
            message.success = false;
            message.message = "Incorrect phase: "+trade.getState().getPhase();
            return message;
        }

        CompletableFuture<Message> promise = new CompletableFuture<>();
        UserThread.execute(()-> {
            ((SellerTrade) trade).onFiatPaymentReceived(() -> {
                message.success = true;
                message.message = "Payment has been confirmed as received";
                promise.complete(message);
            }, (err) -> {
                message.success = false;
                message.message = err;
                promise.complete(message);
            });
        });

        return promise.get();
    }

    @RequestMapping(value = "/payment/movetobisq", method= RequestMethod.POST, produces = APPLICATION_JSON_VALUE)
    @ApiOperation(value = "Move trade funds to the BISQ wallet")
    public Message moveToBisqWallet(
            @RequestParam(value = "The trade offer id", required=true)
                    String tradeId
    ) throws Exception {
        checkErrors();

        Message message = isTrade(tradeId);
        if(message.success.equals(false)) return message;

        Trade trade = tradeManager.getTradeById(tradeId).get();

        if(!trade.getState().getPhase().toString().matches("PAYOUT_PUBLISHED")){
            message.success = false;
            message.message = "Incorrect phase: "+trade.getState().getPhase();
            return message;
        }

        CompletableFuture<Message> promise = new CompletableFuture<>();
        UserThread.execute(()->{
            btcWalletService.swapTradeEntryToAvailableEntry(tradeId, AddressEntry.Context.TRADE_PAYOUT);
            tradeManager.addTradeToClosedTrades(trade);
            message.success = true;
            message.message = "Payout was moved to BISQ wallet";
            promise.complete(message);
        });

        return promise.get();
    }
}
