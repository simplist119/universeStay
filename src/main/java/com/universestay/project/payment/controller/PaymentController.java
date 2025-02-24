package com.universestay.project.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.universestay.project.payment.dto.PaymentDto;
import com.universestay.project.payment.dto.RefundPayDto;
import com.universestay.project.payment.service.PaymentService;
import com.universestay.project.room.service.BookService;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/payment")
@CrossOrigin(origins = "https://api.iamport.kr")
public class PaymentController {

    private PaymentService paymentService;

    private BookService bookService;

    @Autowired
    public PaymentController(PaymentService paymentService, BookService bookService) {
        this.paymentService = paymentService;
        this.bookService = bookService;
    }


    /**
     * 포트원 API를 이용해서 AccessToken 발급 받기(추후 결제 정보 확인용)
     *
     * @return ResponseEntity(data, status)
     * @throws IOException
     */
    @PostMapping("/getAccessToken")
    @ResponseBody
//    public ResponseEntity getAccessToken(@RequestParam("imp_uid") String imp_uid,
//            @RequestParam("merchant_uid") String merchant_uid) throws IOException {
    public ResponseEntity getAccessToken() throws IOException {

        // TODO: 환경변수로 빼기
        final String IMP_KEY = "5372858343674204"; // REST API 키
        final String IMP_SECRET = "jc6Sxc1cbULMvRP40c7cnkPkj73i2VSJWzor9RpxLTSzjkbhnASK4d4Uf5gobqPDl4UIrdCdSiZUbBBm";

        String jsonData =
                "{ \"imp_key\": \"" + IMP_KEY + "\", \"imp_secret\": \"" + IMP_SECRET + "\" }";
        byte[] postData = jsonData.getBytes("UTF-8");

        // 요청 URL 설정
        URL url = new URL("https://api.iamport.kr/users/getToken");

        // HttpURLConnection 열기
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

        // POST 메서드 설정
        urlConnection.setRequestMethod("POST");

        // 요청 헤더 설정
        urlConnection.setRequestProperty("Content-Type", "application/json");
        urlConnection.setRequestProperty("Accept", "application/json");

        // 데이터 전송을 위한 설정
        urlConnection.setDoOutput(true);

        // 데이터 전송을 위한 DataOutputStream 생성
        DataOutputStream outputStream = new DataOutputStream(urlConnection.getOutputStream());
        outputStream.write(postData);
        outputStream.flush();
        outputStream.close();

        // 응답 코드 확인
        int responseCode = urlConnection.getResponseCode();

        // 응답 데이터 읽기
        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream()));
        StringBuffer stringBuffer = new StringBuffer();

        String inputLine = "";
        while ((inputLine = bufferedReader.readLine()) != null) {
            stringBuffer.append(inputLine);
        }
        bufferedReader.close();

        String response = stringBuffer.toString();

        // 연결 닫기
        urlConnection.disconnect();
        return new ResponseEntity(response, HttpStatus.OK);
    }


    /**
     * rsp.imp_uid 값으로 결제 단건조회 API를 호출하여 결제결과를 판단
     *
     * @param imp_uid
     * @param Authorization
     * @return ResponseEntity(data, status)
     * @throws IOException
     */
    @PostMapping("/lookUpImpUid")
    @ResponseBody
    public ResponseEntity lookUpImpUid(
            @RequestParam("imp_uid") String imp_uid,
            @RequestParam("Authorization") String Authorization,
            @RequestParam("booking_id") String booking_id,
            @RequestParam("payment_id") String payment_id
    ) throws IOException {

        // 요청 URL 설정
        // imp_uid 전달
        URL url = new URL("https://api.iamport.kr/payments/" + imp_uid);

        // HttpURLConnection 열기
        HttpURLConnection Connection = (HttpURLConnection) url.openConnection();

        // POST 메서드 설정
        Connection.setRequestMethod("GET");

        // 요청 헤더 설정
        // 인증 토큰 Authorization header에 추가
        Connection.setRequestProperty("Authorization", Authorization);
        Connection.setRequestProperty("Content-Type", "application/json");

        // 응답 코드 확인
        int responseCode = Connection.getResponseCode();

        // 응답 데이터 읽기
        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(Connection.getInputStream()));
        StringBuffer stringBuffer = new StringBuffer();

        String inputLine = "";
        while ((inputLine = bufferedReader.readLine()) != null) {
            stringBuffer.append(inputLine);
        }
        bufferedReader.close();

        String response = stringBuffer.toString();

        // 연결 닫기
        Connection.disconnect();

        /*
         * 1. bookingID를 조회해서 실제 숙소 결제금액과 포트원 API의 결제 금액을 비교하기
         * 2. 검증이 성공하면 결제 정보를 데이터베이스에 저장(UPDATE)
         * 3. 결제 상태(status)에 따라 알맞은 응답을 반환하고, 실패 시 에러 메세지를 출력
         * */

        // Jackson - JSON을 java 객체로 파싱
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(response);

        String status = jsonNode.get("response").get("status").asText();
        Integer amount = Integer.valueOf(String.valueOf(jsonNode.get("response").get("amount")));

        // DB에서 결제되어야 하는 금액 조회(결제 되어야 하는 금액)
        Map<String, Object> orderInfo = paymentService.findOrderById(booking_id);

        Integer amountToBePaid = (Integer) orderInfo.get("booking_total_pay_amount");

        if (amountToBePaid == null) {
            return new ResponseEntity("DB값이 조회되지 않음", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 결제 검증하기, 결제 된 금액 === 결제 되어야 하는 금액

        if (amount.equals(amountToBePaid)) {
            switch (status) {
                // 결제 디비 반영 & 결제 완료 처리
                case "paid":
                    paymentService.updateOrderById(payment_id);
                    return new ResponseEntity(HttpStatus.OK);
            }
        }

        return new ResponseEntity("결제 오류 발생", HttpStatus.INTERNAL_SERVER_ERROR);

    }

    @PostMapping("/getPaymentInfo")
    @ResponseBody
    public ResponseEntity getPaymentInfo(@RequestParam("bookingId") String bookingId) {
        return new ResponseEntity<>(paymentService.findPaymentUser(bookingId), HttpStatus.OK);
    }

    /**
     * 결제 정보를 Payment DB에 저장
     *
     * @param paymentDto
     * @return
     */
    @PostMapping("/saveResponse")
    @ResponseBody
    public ResponseEntity getPaymentResponse(@RequestBody PaymentDto paymentDto) {
        try {
            String uuid = UUID.randomUUID().toString();
            //payment_id에 랜덤 Uuid 부여
            paymentDto.setPayment_id(uuid);
            paymentService.insertPaymentInfo(paymentDto);
            return new ResponseEntity<>(uuid, HttpStatus.OK);
        } catch (Exception e) {
            // 예외가 발생했을 때 실행할 코드
            e.printStackTrace(); // 에러 메시지를 콘솔에 출력하거나 원하는 작업을 수행할 수 있어요.
            return new ResponseEntity<>("Error occurred", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * 결제 관련 API 요청 전 필요한 정보 조회
     *
     * @param bookingId
     * @return
     */
    @PostMapping("/getBookingInfo")
    @ResponseBody
    public ResponseEntity getBookingInfo(@RequestParam("bookingId") String bookingId) {
        return new ResponseEntity<>(paymentService.findBookingById(bookingId), HttpStatus.OK);
    }

    /**
     * 예약 취소 및 결제 취소 API
     *
     * @param refundPayDto
     * @return
     * @throws IOException
     */
    @PostMapping("/refundPay")
    public ResponseEntity refundPay(@RequestBody RefundPayDto refundPayDto)
            throws IOException {

        // 클라이언트의 요청받은 정보로 payment에 대한 정보를 DB에서 조회
        Map<String, Object> paymentInfo = paymentService.findPaymentById(
                refundPayDto.getMerchant_uid());

        // 결제 정보 조회 확인
        if (paymentInfo == null || paymentInfo.size() == 0) {
            return new ResponseEntity("결제 정보가 조회되지 않습니다.", HttpStatus.BAD_REQUEST);
        }

        // 포트원에서 결제 정보 가져오기
        final String IMPORT_CANCEL_URL = "https://api.iamport.kr/payments/cancel";

        // 전송할 데이터
        String jsonData = "{ \"merchant_uid\": \"" + refundPayDto.getMerchant_uid() + "\" }";
        byte[] postData = jsonData.getBytes("UTF-8");

        // 토큰 정보 가져오기
        ResponseEntity responseBody = getAccessToken();

        // Jackson ObjectMapper를 사용하여 JSON 파싱
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree((String) responseBody.getBody());

        // "response" 객체에서 "access_token" 값을 가져옴
        String accessToken = jsonNode.path("response").path("access_token").asText();

        // 요청 URL 설정(포트원 결제 취소 API)
        URL url = new URL(IMPORT_CANCEL_URL);

        // HttpURLConnection 열기
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

        // POST 메서드 설정
        urlConnection.setRequestMethod("POST");

        // 요청 헤더 설정
        urlConnection.setRequestProperty("Authorization", accessToken);
        urlConnection.setRequestProperty("Content-Type", "application/json");
        urlConnection.setRequestProperty("Accept", "application/json");

        // 데이터 전송을 위한 설정
        urlConnection.setDoOutput(true);

        // 데이터 전송을 위한 DataOutputStream 생성
        DataOutputStream outputStream = new DataOutputStream(urlConnection.getOutputStream());
        outputStream.write(postData);
        outputStream.flush();
        outputStream.close();

        // 응답 코드 확인
        int responseCode = urlConnection.getResponseCode();

        // 응답 데이터 읽기
        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream()));
        StringBuffer stringBuffer = new StringBuffer();

        String inputLine = "";
        while ((inputLine = bufferedReader.readLine()) != null) {
            stringBuffer.append(inputLine);
        }
        bufferedReader.close();

        String response = stringBuffer.toString();

        // 연결 닫기
        urlConnection.disconnect();

        String code = jsonNode.path("code").asText();
        // 포트원에서도 정상적인 취소 처리가 완료된다면 DB에 결제 취소 반영
        if (responseCode == 200 && code.equals("0")) {
            paymentService.updatePaymentById(paymentInfo.get("payment_id"));
            bookService.updateStatus((String) paymentInfo.get("booking_id"));
            return new ResponseEntity(HttpStatus.OK);
        }

        return new ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * 예약 취소 API
     *
     * @param params
     * @return
     * @throws IOException
     */
    @PostMapping("/canclePay")
    public ResponseEntity canclePay(@RequestBody String params) throws IOException {
        // 클라이언트의 요청받은 정보로 payment에 대한 정보를 DB에서 조회

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(params);

        String bookingId = jsonNode.path("bookingId").asText();

        // is_approved 상태 'C'로 변경
        paymentService.updatePaymentStatusByBookingId(bookingId);
        bookService.updateStatus(bookingId);

        return new ResponseEntity(HttpStatus.OK);
    }


}
