package com.casamento.casamento_api.controller;

import com.casamento.casamento_api.model.Presente;
import com.casamento.casamento_api.model.StatusPresente;
import com.casamento.casamento_api.repository.PresenteRepository;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.*;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/presentes")
@CrossOrigin(origins = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class PresenteController {
    // ... restante do código

    private final PresenteRepository repository;

    public PresenteController(PresenteRepository repository,
                              @Value("${mercadopago.access-token}") String mpToken) {
        this.repository = repository;
        MercadoPagoConfig.setAccessToken(mpToken);
    }

    // 1. Endpoint que o site vai consultar para listar apenas os presentes DISPONÍVEIS
    @GetMapping
    public List<Presente> listarDisponiveis() {
        return repository.findByStatus(StatusPresente.DISPONIVEL);
    }

    // 2. Endpoint chamado quando o convidado clica em "Presentear"
    // 2. Endpoint chamado quando o convidado clica em "Presentear"
    @PostMapping("/{id}/checkout")
    public ResponseEntity<Map<String, String>> criarCheckout(@PathVariable Long id) {
        Presente presente = repository.findById(id).orElseThrow();

        if (presente.getStatus() == StatusPresente.COMPRADO) {
            return ResponseEntity.badRequest().body(Map.of("error", "Este presente já foi adquirido!"));
        }

        try {
            // Garante que o valor tenha 2 casas decimais
            BigDecimal valorUnitario = presente.getValor().setScale(2, java.math.RoundingMode.HALF_UP);

            PreferenceItemRequest itemRequest = PreferenceItemRequest.builder()
                    .id(String.valueOf(presente.getId()))
                    .title(presente.getNome())
                    .description(presente.getDescricao() != null ? presente.getDescricao() : "Presente de Casamento")
                    .quantity(1)
                    .unitPrice(valorUnitario)
                    .currencyId("BRL")
                    .build();

            PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                    .items(List.of(itemRequest))
                    .externalReference(String.valueOf(presente.getId()))
                    .build();

            PreferenceClient client = new PreferenceClient();
            Preference preference = client.create(preferenceRequest);

            return ResponseEntity.ok(Map.of("initPoint", preference.getInitPoint()));
        } catch (com.mercadopago.exceptions.MPApiException apiException) {
            System.err.println(">>> ERRO DETALHADO DO MERCADO PAGO: " + apiException.getApiResponse().getContent());
            apiException.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", apiException.getApiResponse().getContent()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Erro desconhecido"));
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
                        if (payment.getPayer() != null && payment.getPayer().getFirstName() != null) {
                            p.setCompradorNome(payment.getPayer().getFirstName());
                        } else {
                            p.setCompradorNome("Convidado Anônimo");
                        }
                        repository.save(p);
                    });
                }
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
    // 4. ADMIN: Listar TODOS os presentes (disponíveis e comprados)
    @GetMapping("/todos")
    public List<Presente> listarTodos() {
        return repository.findAll();
    }

    // 5. ADMIN: Cadastrar um novo presente
    @PostMapping
    public ResponseEntity<Presente> cadastrar(@RequestBody Presente novoPresente) {
        novoPresente.setStatus(StatusPresente.DISPONIVEL);
        Presente salvo = repository.save(novoPresente);
        return ResponseEntity.ok(salvo);
    }

    // 6. ADMIN: Excluir um presente
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletar(@PathVariable Long id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // 7. ADMIN: Atualizar/Editar um presente existente
    @PutMapping("/{id}")
    public ResponseEntity<Presente> atualizar(@PathVariable Long id, @RequestBody Presente dadosAtualizados) {
        return repository.findById(id).map(presente -> {
            presente.setNome(dadosAtualizados.getNome());
            presente.setValor(dadosAtualizados.getValor());
            presente.setDescricao(dadosAtualizados.getDescricao());

            // Só atualiza a foto se uma nova imagem tiver sido enviada
            if (dadosAtualizados.getImagemUrl() != null && !dadosAtualizados.getImagemUrl().isEmpty()) {
                presente.setImagemUrl(dadosAtualizados.getImagemUrl());
            }

            // Permite alterar o status pelo Admin (ex: reativar um presente)
            if (dadosAtualizados.getStatus() != null) {
                presente.setStatus(dadosAtualizados.getStatus());
            }

            Presente atualizado = repository.save(presente);
            return ResponseEntity.ok(atualizado);
        }).orElse(ResponseEntity.notFound().build());
    }
}