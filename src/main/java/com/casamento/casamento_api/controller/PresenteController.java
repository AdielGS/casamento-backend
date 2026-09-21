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
        try {
            Long presenteId = Long.parseLong(brickData.get("presenteId").toString());
            Presente presente = repository.findById(presenteId)
                    .orElseThrow(() -> new RuntimeException("Presente não encontrado"));

            if (presente.getStatus() == StatusPresente.COMPRADO) {
                return ResponseEntity.badRequest().body(Map.of("error", "Este presente já foi adquirido!"));
            }

            // Registar nome do convidado
            String nomeConvidado = "Convidado";
            if (brickData.containsKey("compradorNome") && brickData.get("compradorNome") != null) {
                String nomeInformado = brickData.get("compradorNome").toString().trim();
                if (!nomeInformado.isBlank()) {
                    nomeConvidado = nomeInformado;
                    presente.setCompradorNome(nomeConvidado);
                }
            }

            // Dividir em primeiro e último nome
            String[] partesNome = nomeConvidado.split(" ", 2);
            String firstName = partesNome[0];
            String lastName = partesNome.length > 1 ? partesNome[1] : "Convidado";

            // Valor do presente com duas casas decimais
            BigDecimal transactionAmount = presente.getValor().setScale(2, java.math.RoundingMode.HALF_UP);
            String paymentMethodId = brickData.get("payment_method_id") != null
                    ? brickData.get("payment_method_id").toString()
                    : "pix";

            // Tratar dados do pagador
            Map<String, Object> payerMap = (Map<String, Object>) brickData.get("payer");
            String email = (payerMap != null && payerMap.get("email") != null && !payerMap.get("email").toString().isBlank())
                    ? payerMap.get("email").toString()
                    : "convidado_" + presente.getId() + "@casamento.com";

            if (payerMap != null && payerMap.get("first_name") != null && !payerMap.get("first_name").toString().isBlank()) {
                firstName = payerMap.get("first_name").toString();
            }
            if (payerMap != null && payerMap.get("last_name") != null && !payerMap.get("last_name").toString().isBlank()) {
                lastName = payerMap.get("last_name").toString();
            }

            PaymentPayerRequest.PaymentPayerRequestBuilder payerBuilder = PaymentPayerRequest.builder()
                    .email(email)
                    .firstName(firstName)
                    .lastName(lastName);

            // Documento de identificação (CPF)
            if (payerMap != null && payerMap.containsKey("identification")) {
                Map<String, Object> identMap = (Map<String, Object>) payerMap.get("identification");
                if (identMap != null && identMap.get("number") != null) {
                    String docNumber = identMap.get("number").toString().replaceAll("\\D", "");
                    String docType = identMap.get("type") != null ? identMap.get("type").toString() : "CPF";
                    if (!docNumber.isBlank()) {
                        payerBuilder.identification(
                                IdentificationRequest.builder()
                                        .type(docType)
                                        .number(docNumber)
                                        .build()
                        );
                    }
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
                paymentBuilder.token(brickData.get("token").toString());
                if (brickData.containsKey("installments") && brickData.get("installments") != null) {
                    paymentBuilder.installments(Integer.parseInt(brickData.get("installments").toString()));
                }
                if (brickData.containsKey("issuer_id") && brickData.get("issuer_id") != null) {
                    paymentBuilder.issuerId(brickData.get("issuer_id").toString());
                }
            }

            PaymentClient client = new PaymentClient();
            Payment payment = client.create(paymentBuilder.build());

            if ("approved".equals(payment.getStatus())) {
                presente.setStatus(StatusPresente.COMPRADO);
                presente.setPaymentId(String.valueOf(payment.getId()));
                repository.save(presente);
            } else {
                repository.save(presente);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("id", payment.getId());
            response.put("status", payment.getStatus());
            response.put("status_detail", payment.getStatusDetail());

            if (payment.getPointOfInteraction() != null &&
                    payment.getPointOfInteraction().getTransactionData() != null) {
                response.put("qr_code", payment.getPointOfInteraction().getTransactionData().getQrCode());
                response.put("qr_code_base64", payment.getPointOfInteraction().getTransactionData().getQrCodeBase64());
            }

            if (payment.getTransactionDetails() != null && payment.getTransactionDetails().getExternalResourceUrl() != null) {
                response.put("ticket_url", payment.getTransactionDetails().getExternalResourceUrl());
            }

            return ResponseEntity.ok(response);

        } catch (com.mercadopago.exceptions.MPApiException apiException) {
            String detalhesErro = apiException.getApiResponse() != null ? apiException.getApiResponse().getContent() : apiException.getMessage();
            System.err.println(">>> ERRO DETALHADO DO MERCADO PAGO: " + detalhesErro);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", detalhesErro));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"));
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