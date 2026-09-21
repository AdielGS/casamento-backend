package com.casamento.casamento_api.controller;

import com.casamento.casamento_api.model.Presente;
import com.casamento.casamento_api.model.StatusPresente;
import com.casamento.casamento_api.repository.PresenteRepository;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.*;
import com.mercadopago.resources.payment.Payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/presentes")
@CrossOrigin(
        origins = "*",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS}
)
public class PresenteController {

    private final PresenteRepository repository;

    public PresenteController(PresenteRepository repository,
                              @Value("${mercadopago.access-token}") String mpToken) {
        this.repository = repository;
        MercadoPagoConfig.setAccessToken(mpToken);
    }

    // 1. Listar apenas os presentes DISPONÍVEIS
    @GetMapping
    public List<Presente> listarDisponiveis() {
        return repository.findByStatus(StatusPresente.DISPONIVEL);
    }

    // 2. Processar Pagamento vindo do Checkout Bricks (Pix, Cartão, Boleto)
    @CrossOrigin(origins = "*", allowedHeaders = "*")
    @PostMapping("/processar-pagamento")
    public ResponseEntity<?> processarPagamento(@RequestBody Map<String, Object> brickData) {
        // ... restante do método igual
        try {
            Long presenteId = Long.parseLong(brickData.get("presenteId").toString());
            Presente presente = repository.findById(presenteId)
                    .orElseThrow(() -> new RuntimeException("Presente não encontrado"));

            if (presente.getStatus() == StatusPresente.COMPRADO) {
                return ResponseEntity.badRequest().body(Map.of("error", "Este presente já foi adquirido!"));
            }

            // Captura o nome do convidado se enviado
            if (brickData.containsKey("compradorNome") && brickData.get("compradorNome") != null) {
                presente.setCompradorNome(brickData.get("compradorNome").toString().trim());
            }

            BigDecimal transactionAmount = new BigDecimal(brickData.get("transaction_amount").toString())
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            String paymentMethodId = (String) brickData.get("payment_method_id");

            // Dados do pagador
            Map<String, Object> payerMap = (Map<String, Object>) brickData.get("payer");
            String email = payerMap != null && payerMap.containsKey("email") ? (String) payerMap.get("email") : "convidado@casamento.com";

            PaymentPayerRequest.PaymentPayerRequestBuilder payerBuilder = PaymentPayerRequest.builder()
                    .email(email);

            if (payerMap != null && payerMap.containsKey("identification")) {
                Map<String, Object> identMap = (Map<String, Object>) payerMap.get("identification");
                if (identMap != null && identMap.get("type") != null && identMap.get("number") != null) {
                    payerBuilder.identification(
                            com.mercadopago.client.common.IdentificationRequest.builder()
                                    .type((String) identMap.get("type"))
                                    .number((String) identMap.get("number"))
                                    .build()
                    );
                }
            }

            PaymentCreateRequest.PaymentCreateRequestBuilder paymentBuilder = PaymentCreateRequest.builder()
                    .transactionAmount(transactionAmount)
                    .description("Presente: " + presente.getNome())
                    .paymentMethodId(paymentMethodId)
                    .payer(payerBuilder.build())
                    .externalReference(String.valueOf(presente.getId()));

            // Se for Cartão de Crédito
            if (brickData.containsKey("token") && brickData.get("token") != null) {
                paymentBuilder.token((String) brickData.get("token"));
                if (brickData.containsKey("installments") && brickData.get("installments") != null) {
                    paymentBuilder.installments(Integer.parseInt(brickData.get("installments").toString()));
                }
                if (brickData.containsKey("issuer_id") && brickData.get("issuer_id") != null) {
                    paymentBuilder.issuerId((String) brickData.get("issuer_id"));
                }
            }

            PaymentClient client = new PaymentClient();
            Payment payment = client.create(paymentBuilder.build());

            // Se aprovado na hora (ex: Cartão de crédito aprovado)
            if ("approved".equals(payment.getStatus())) {
                presente.setStatus(StatusPresente.COMPRADO);
                presente.setPaymentId(String.valueOf(payment.getId()));
                repository.save(presente);
            } else {
                // Salva o nome e aguarda o webhook para Pix/Boleto
                repository.save(presente);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", payment.getId());
            response.put("status", payment.getStatus());
            response.put("status_detail", payment.getStatusDetail());

            // Se for Pix, envia o QR Code e a chave Copia e Cola para o frontend exibir
            if (payment.getPointOfInteraction() != null &&
                    payment.getPointOfInteraction().getTransactionData() != null) {
                response.put("qr_code", payment.getPointOfInteraction().getTransactionData().getQrCode());
                response.put("qr_code_base64", payment.getPointOfInteraction().getTransactionData().getQrCodeBase64());
            }

            // Se for Boleto, envia o link do boleto
            if (payment.getTransactionDetails() != null && payment.getTransactionDetails().getExternalResourceUrl() != null) {
                response.put("ticket_url", payment.getTransactionDetails().getExternalResourceUrl());
            }

            return ResponseEntity.ok(response);

        } catch (com.mercadopago.exceptions.MPApiException apiException) {
            System.err.println(">>> ERRO MERCADO PAGO BRICKS: " + apiException.getApiResponse().getContent());
            return ResponseEntity.badRequest().body(Map.of("error", apiException.getApiResponse().getContent()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"));
        }
    }

    // 3. Webhook: O Mercado Pago avisa automaticamente quando o Pix/Cartão é aprovado
    @PostMapping("/webhook")
    public ResponseEntity<Void> processarWebhook(@RequestParam(value = "type", required = false) String type,
                                                 @RequestParam(value = "data.id", required = false) String dataId,
                                                 @RequestBody(required = false) Map<String, Object> payload) {
        try {
            if ("payment".equals(type) && dataId != null) {
                PaymentClient paymentClient = new PaymentClient();
                Payment payment = paymentClient.get(Long.parseLong(dataId));

                if ("approved".equals(payment.getStatus())) {
                    Long presenteId = Long.parseLong(payment.getExternalReference());
                    repository.findById(presenteId).ifPresent(p -> {
                        p.setStatus(StatusPresente.COMPRADO);
                        p.setPaymentId(dataId);
                        repository.save(p);
                    });
                }
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // 4. ADMIN: Listar TODOS os presentes
    @GetMapping("/todos")
    public List<Presente> listarTodos() {
        return repository.findAll();
    }

    // 5. ADMIN: Cadastrar
    @PostMapping
    public ResponseEntity<Presente> cadastrar(@RequestBody Presente novoPresente) {
        novoPresente.setStatus(StatusPresente.DISPONIVEL);
        Presente salvo = repository.save(novoPresente);
        return ResponseEntity.ok(salvo);
    }

    // 6. ADMIN: Excluir
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // 7. ADMIN: Editar
    @PutMapping("/{id}")
    public ResponseEntity<Presente> atualizar(@PathVariable Long id, @RequestBody Presente dadosAtualizados) {
        return repository.findById(id).map(presente -> {
            presente.setNome(dadosAtualizados.getNome());
            presente.setValor(dadosAtualizados.getValor());
            presente.setDescricao(dadosAtualizados.getDescricao());

            if (dadosAtualizados.getImagemUrl() != null && !dadosAtualizados.getImagemUrl().isEmpty()) {
                presente.setImagemUrl(dadosAtualizados.getImagemUrl());
            }
            if (dadosAtualizados.getStatus() != null) {
                presente.setStatus(dadosAtualizados.getStatus());
            }

            Presente atualizado = repository.save(presente);
            return ResponseEntity.ok(atualizado);
        }).orElse(ResponseEntity.notFound().build());
    }
}