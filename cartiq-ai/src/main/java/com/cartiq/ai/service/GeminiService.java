package com.cartiq.ai.service;

import com.cartiq.kafka.dto.KafkaEvents.AISearchEvent;
import com.cartiq.kafka.producer.EventProducer;
import com.cartiq.product.dto.CategoryDTO;
import com.cartiq.product.dto.ProductDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Service for interacting with Google Gemini via Vertex AI.
 * Implements function calling to query products based on user intent.
 */
@Slf4j
@Service
public class GeminiService {

    private final ProductToolService productToolService;
    private final EventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final Client client;
    private final String modelName;

    private static final int MAX_FUNCTION_CALLS = 5;

    public GeminiService(
            ProductToolService productToolService,
            EventProducer eventProducer,
            ObjectMapper objectMapper,
            @Value("${vertex.ai.project-id:}") String projectId,
            @Value("${vertex.ai.location:us-central1}") String location,
            @Value("${vertex.ai.model:gemini-2.0-flash}") String modelName) {
        this.productToolService = productToolService;
        this.eventProducer = eventProducer;
        this.objectMapper = objectMapper;
        this.modelName = modelName;

        // Initialize Vertex AI client
        Client tempClient = null;
        if (projectId != null && !projectId.isBlank()) {
            try {
                tempClient = Client.builder()
                        .project(projectId)
                        .location(location)
                        .vertexAI(true)
                        .build();
                log.info("Initialized Gemini client for Vertex AI: project={}, location={}, model={}",
                        projectId, location, modelName);
            } catch (Exception e) {
                log.error("Failed to initialize Gemini client: {}", e.getMessage());
                tempClient = null;
            }
        } else {
            log.warn("Vertex AI not configured - projectId is empty. Set vertex.ai.project-id property.");
        }
        this.client = tempClient;
    }

    /**
     * Process a chat message with function calling support.
     */
    public ChatResult chat(String userMessage, String userId, String sessionId, Map<String, Object> userContext) {
        long startTime = System.currentTimeMillis();

        if (client == null) {
            log.warn("Gemini client not initialized, returning mock response");
            return createMockResponse(userMessage);
        }

        try {
            // Build the system prompt
            String systemPrompt = buildSystemPrompt(userContext);

            // Define available tools
            List<Tool> tools = List.of(buildProductTools());

            // Create content with user message
            List<Content> contents = new ArrayList<>();
            contents.add(Content.builder()
                    .role("user")
                    .parts(List.of(Part.builder().text(userMessage).build()))
                    .build());

            // Generate content with tools
            GenerateContentConfig config = GenerateContentConfig.builder()
                    .systemInstruction(Content.builder()
                            .parts(List.of(Part.builder().text(systemPrompt).build()))
                            .build())
                    .tools(tools)
                    .build();

            GenerateContentResponse response = client.models.generateContent(
                    modelName,
                    contents,
                    config
            );

            // Process response and handle function calls
            ChatResult result = processResponse(response, contents, config, userId, sessionId, userMessage, startTime);

            log.info("Chat completed in {}ms", System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("Error calling Gemini: {}", e.getMessage(), e);
            return createMockResponse(userMessage);
        }
    }

    /**
     * Process Gemini response, handling function calls if present.
     */
    private ChatResult processResponse(
            GenerateContentResponse response,
            List<Content> contents,
            GenerateContentConfig config,
            String userId,
            String sessionId,
            String originalQuery,
            long startTime) {

        List<ProductDTO> allProducts = new ArrayList<>();
        Set<String> executedCalls = new HashSet<>(); // Track executed function calls to avoid duplicates
        int functionCallCount = 0;

        // Loop to handle multiple function calls
        while (functionCallCount < MAX_FUNCTION_CALLS) {
            // Get candidate safely using Optional
            Optional<List<Candidate>> candidatesOpt = response.candidates();
            if (candidatesOpt.isEmpty() || candidatesOpt.get().isEmpty()) {
                return ChatResult.builder()
                        .message("I'm sorry, I couldn't process your request.")
                        .products(allProducts)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            Candidate candidate = candidatesOpt.get().get(0);
            Optional<Content> contentOpt = candidate.content();
            if (contentOpt.isEmpty()) {
                return ChatResult.builder()
                        .message("I'm sorry, I couldn't generate a response.")
                        .products(allProducts)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            Content content = contentOpt.get();

            // Check for function calls in the response
            List<FunctionCall> functionCalls = extractFunctionCalls(content);

            if (functionCalls.isEmpty()) {
                // No more function calls - extract final text response
                String textResponse = extractTextResponse(content);
                return ChatResult.builder()
                        .message(textResponse)
                        .products(allProducts)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Execute function calls and collect results
            List<Part> functionResponses = new ArrayList<>();
            boolean anyNewCalls = false;

            for (FunctionCall functionCall : functionCalls) {
                String funcName = functionCall.name().orElse("unknown");
                Map<String, Object> args = functionCall.args().orElse(Map.of());

                // Create a unique key for this function call to detect duplicates
                String callKey = funcName + ":" + args.toString();

                if (executedCalls.contains(callKey)) {
                    log.debug("Skipping duplicate function call: {}", callKey);
                    // Return cached result for duplicate call
                    functionResponses.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .name(funcName)
                                    .response(Map.of("status", "already_executed", "message", "This search was already performed"))
                                    .build())
                            .build());
                    continue;
                }

                executedCalls.add(callKey);
                anyNewCalls = true;

                FunctionCallResult fcResult = executeFunctionCall(funcName, args, userId, sessionId, originalQuery, startTime);
                allProducts.addAll(fcResult.products);

                // Create function response part
                functionResponses.add(Part.builder()
                        .functionResponse(FunctionResponse.builder()
                                .name(funcName)
                                .response(fcResult.responseData)
                                .build())
                        .build());
            }

            // If all calls were duplicates, break out of the loop
            if (!anyNewCalls) {
                log.debug("All function calls were duplicates, stopping iteration");
                break;
            }

            // Add assistant's function call to conversation
            contents.add(content);

            // Add function responses to conversation
            contents.add(Content.builder()
                    .role("user")
                    .parts(functionResponses)
                    .build());

            // Continue conversation with function results
            try {
                response = client.models.generateContent(modelName, contents, config);
            } catch (Exception e) {
                log.error("Error in function call continuation: {}", e.getMessage());
                break;
            }

            functionCallCount++;
        }

        // Fallback if we exceed max function calls
        return ChatResult.builder()
                .message("I found some products for you. Let me know if you'd like more details!")
                .products(allProducts)
                .processingTimeMs(System.currentTimeMillis() - startTime)
                .build();
    }

    /**
     * Extract function calls from content.
     */
    private List<FunctionCall> extractFunctionCalls(Content content) {
        List<FunctionCall> calls = new ArrayList<>();
        Optional<List<Part>> partsOpt = content.parts();
        if (partsOpt.isPresent()) {
            for (Part part : partsOpt.get()) {
                Optional<FunctionCall> fcOpt = part.functionCall();
                if (fcOpt.isPresent()) {
                    calls.add(fcOpt.get());
                }
            }
        }
        return calls;
    }

    /**
     * Extract text response from content.
     */
    private String extractTextResponse(Content content) {
        Optional<List<Part>> partsOpt = content.parts();
        if (partsOpt.isPresent()) {
            for (Part part : partsOpt.get()) {
                Optional<String> textOpt = part.text();
                if (textOpt.isPresent() && !textOpt.get().isBlank()) {
                    return textOpt.get();
                }
            }
        }
        return "I found some products that might interest you!";
    }

    /**
     * Execute a function call and return results.
     */
    private FunctionCallResult executeFunctionCall(
            String functionName,
            Map<String, Object> args,
            String userId,
            String sessionId,
            String originalQuery,
            long startTime) {

        log.info("Executing function: {} with args: {}", functionName, args);

        List<ProductDTO> products = new ArrayList<>();
        Map<String, Object> responseData = new HashMap<>();

        try {
            switch (functionName) {
                case "searchProducts" -> {
                    String query = (String) args.get("query");
                    String category = (String) args.get("category");
                    Double minPrice = toDouble(args.get("minPrice"));
                    Double maxPrice = toDouble(args.get("maxPrice"));
                    Double minRating = toDouble(args.get("minRating"));

                    log.info("searchProducts params: query='{}', category='{}', minPrice={}, maxPrice={}, minRating={}",
                            query, category, minPrice, maxPrice, minRating);

                    products = productToolService.executeSearchProducts(query, category, minPrice, maxPrice, minRating);
                    log.info("searchProducts returned {} products", products.size());
                    responseData.put("products", productsToMap(products));
                    responseData.put("count", products.size());

                    // Emit Kafka event - searchProducts uses HYBRID (Vector + FTS + Reranker)
                    emitAISearchEvent(userId, sessionId, originalQuery, functionName, "HYBRID",
                            category, minPrice, maxPrice, minRating, products, startTime);
                }

                case "getProductDetails" -> {
                    String productId = (String) args.get("productId");
                    String productName = (String) args.get("productName");

                    ProductDTO product = productToolService.executeGetProductDetails(productId, productName);
                    if (product != null) {
                        products.add(product);
                        responseData.put("product", productToMap(product));
                    } else {
                        responseData.put("error", "Product not found");
                    }

                    // FTS - database lookup
                    emitAISearchEvent(userId, sessionId, originalQuery, functionName, "FTS",
                            null, null, null, null, products, startTime);
                }

                case "getCategories" -> {
                    List<CategoryDTO> categories = productToolService.executeGetCategories();
                    responseData.put("categories", categories.stream()
                            .map(c -> Map.of("id", c.getId().toString(), "name", c.getName()))
                            .toList());
                    responseData.put("count", categories.size());
                }

                case "getFeaturedProducts" -> {
                    products = productToolService.executeGetFeaturedProducts();
                    responseData.put("products", productsToMap(products));
                    responseData.put("count", products.size());

                    // FTS - database query
                    emitAISearchEvent(userId, sessionId, originalQuery, functionName, "FTS",
                            null, null, null, null, products, startTime);
                }

                case "compareProducts" -> {
                    @SuppressWarnings("unchecked")
                    List<String> productNames = (List<String>) args.get("productNames");
                    products = productToolService.executeCompareProducts(productNames);
                    responseData.put("products", productsToMap(products));
                    responseData.put("count", products.size());

                    // FTS - database lookups
                    emitAISearchEvent(userId, sessionId, originalQuery, functionName, "FTS",
                            null, null, null, null, products, startTime);
                }

                case "getProductsByBrand" -> {
                    String brand = (String) args.get("brand");
                    products = productToolService.executeGetProductsByBrand(brand);
                    responseData.put("products", productsToMap(products));
                    responseData.put("count", products.size());

                    // FTS - database query by brand
                    emitAISearchEvent(userId, sessionId, originalQuery, functionName, "FTS",
                            null, null, null, null, products, startTime);
                }

                default -> {
                    log.warn("Unknown function: {}", functionName);
                    responseData.put("error", "Unknown function");
                }
            }
        } catch (Exception e) {
            log.error("Error executing function {}: {}", functionName, e.getMessage());
            responseData.put("error", e.getMessage());
        }

        return new FunctionCallResult(products, responseData);
    }

    /**
     * Build the tool definitions for Gemini.
     */
    private Tool buildProductTools() {
        return Tool.builder()
                .functionDeclarations(List.of(
                        // searchProducts
                        FunctionDeclaration.builder()
                                .name("searchProducts")
                                .description("Search for products with optional filters. Use this when user wants to find products by category, price range, or search query.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of(
                                                "query", Schema.builder()
                                                        .type(Type.Known.STRING)
                                                        .description("Search text for product name, description, or brand")
                                                        .build(),
                                                "category", Schema.builder()
                                                        .type(Type.Known.STRING)
                                                        .description("Product category to filter by (e.g., 'Electronics', 'Clothing')")
                                                        .build(),
                                                "minPrice", Schema.builder()
                                                        .type(Type.Known.NUMBER)
                                                        .description("Minimum price in INR")
                                                        .build(),
                                                "maxPrice", Schema.builder()
                                                        .type(Type.Known.NUMBER)
                                                        .description("Maximum price in INR")
                                                        .build(),
                                                "minRating", Schema.builder()
                                                        .type(Type.Known.NUMBER)
                                                        .description("Minimum rating (0-5)")
                                                        .build()
                                        ))
                                        .build())
                                .build(),

                        // getProductDetails
                        FunctionDeclaration.builder()
                                .name("getProductDetails")
                                .description("Get detailed information about a specific product. Use when user asks about a specific product.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of(
                                                "productId", Schema.builder()
                                                        .type(Type.Known.STRING)
                                                        .description("Product UUID")
                                                        .build(),
                                                "productName", Schema.builder()
                                                        .type(Type.Known.STRING)
                                                        .description("Product name to search for")
                                                        .build()
                                        ))
                                        .build())
                                .build(),

                        // getCategories
                        FunctionDeclaration.builder()
                                .name("getCategories")
                                .description("Get all available product categories. Use when user asks what categories are available.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of())
                                        .build())
                                .build(),

                        // getFeaturedProducts
                        FunctionDeclaration.builder()
                                .name("getFeaturedProducts")
                                .description("Get featured/popular products. Use when user asks for recommendations or popular items.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of())
                                        .build())
                                .build(),

                        // compareProducts
                        FunctionDeclaration.builder()
                                .name("compareProducts")
                                .description("Compare multiple products. Use when user wants to compare specific products.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of(
                                                "productNames", Schema.builder()
                                                        .type(Type.Known.ARRAY)
                                                        .items(Schema.builder().type(Type.Known.STRING).build())
                                                        .description("List of product names to compare")
                                                        .build()
                                        ))
                                        .required(List.of("productNames"))
                                        .build())
                                .build(),

                        // getProductsByBrand
                        FunctionDeclaration.builder()
                                .name("getProductsByBrand")
                                .description("Get products from a specific brand. Use when user asks for products from a brand.")
                                .parameters(Schema.builder()
                                        .type(Type.Known.OBJECT)
                                        .properties(Map.of(
                                                "brand", Schema.builder()
                                                        .type(Type.Known.STRING)
                                                        .description("Brand name")
                                                        .build()
                                        ))
                                        .required(List.of("brand"))
                                        .build())
                                .build()
                ))
                .build();
    }

    /**
     * Build system prompt with user context.
     */
    private String buildSystemPrompt(Map<String, Object> userContext) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are CartIQ, a helpful AI shopping assistant for an Indian e-commerce platform. ");
        prompt.append("Help users find products, compare items, and make purchase decisions. ");
        prompt.append("Use the available tools to search for real products from our catalog. ");
        prompt.append("Always be helpful, concise, and recommend products based on user needs. ");
        prompt.append("Prices are in Indian Rupees (₹). ");

        prompt.append("""

                Here are some examples of how to use the tools:

                **User:** "Show me some wireless headphones"
                **Tool Call:** `searchProducts(query="wireless headphones")`

                **User:** "Find laptops under ₹50000"
                **Tool Call:** `searchProducts(query="laptops", maxPrice=50000)`

                **User:** "Recommend me some good running shoes from Nike"
                **Tool Call:** `getProductsByBrand(brand="Nike")`

                **User:** "Compare the Samsung Galaxy S23 and iPhone 15"
                **Tool Call:** `compareProducts(productNames=["Samsung Galaxy S23", "iPhone 15"])`
                """);

        prompt.append("\n\n");

        if (userContext != null && !userContext.isEmpty()) {
            prompt.append("USER CONTEXT:\n");
            if (userContext.containsKey("pricePreference")) {
                prompt.append("- Price preference: ").append(userContext.get("pricePreference")).append("\n");
            }
            if (userContext.containsKey("preferredCategories")) {
                prompt.append("- Interested in: ").append(userContext.get("preferredCategories")).append("\n");
            }
            if (userContext.containsKey("recentlyViewed")) {
                prompt.append("- Recently viewed products\n");
            }
        }

        prompt.append("\nWhen showing products, mention key details like name, price, rating, and why it's relevant.");
        return prompt.toString();
    }

    /**
     * Create mock response when Gemini is not available.
     */
    private ChatResult createMockResponse(String userMessage) {
        String response;
        String lowerMsg = userMessage.toLowerCase();

        if (lowerMsg.contains("laptop") || lowerMsg.contains("computer")) {
            response = "I'd recommend checking out our laptop collection! We have options from brands like Dell, HP, and Lenovo. What's your budget range?";
        } else if (lowerMsg.contains("phone") || lowerMsg.contains("mobile")) {
            response = "Looking for a smartphone? We have great options from Apple, Samsung, and OnePlus. What features matter most to you?";
        } else if (lowerMsg.contains("headphone") || lowerMsg.contains("earphone")) {
            response = "For audio, I'd suggest looking at Sony, JBL, or boAt. Do you prefer over-ear headphones or earbuds?";
        } else {
            response = "I'm here to help you find the perfect products! You can ask me to search for items, compare products, or get recommendations.";
        }

        return ChatResult.builder()
                .message(response)
                .products(List.of())
                .processingTimeMs(0L)
                .build();
    }

    /**
     * Emit Kafka event for AI search.
     */
    private void emitAISearchEvent(
            String userId,
            String sessionId,
            String originalQuery,
            String toolName,
            String searchType,
            String category,
            Double minPrice,
            Double maxPrice,
            Double minRating,
            List<ProductDTO> products,
            long startTime) {

        try {
            // Infer category from actual search results if not provided
            // This captures what user actually found, not what Gemini guessed
            String effectiveCategory = category;
            if ((effectiveCategory == null || effectiveCategory.isBlank()) && !products.isEmpty()) {
                // Use the MOST COMMON category from top N products (not just the first one)
                // This is more robust against a few irrelevant results from vector search
                effectiveCategory = inferMostCommonCategory(products, 10);
                log.info("Inferred category '{}' from {} search results (most common from top 10)",
                        effectiveCategory, products.size());
            }

            // Build AI search event with all fields populated
            // Primitive types ensure non-null values for Avro schema
            AISearchEvent event = AISearchEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .userId(userId != null ? userId : "anonymous")
                    .sessionId(sessionId != null ? sessionId : "")
                    .query(originalQuery != null ? originalQuery : "")
                    .searchType(searchType != null ? searchType : "UNKNOWN")
                    .toolName(toolName != null ? toolName : "")
                    .category(effectiveCategory != null ? effectiveCategory : "")
                    .minPrice(minPrice != null ? minPrice : 0.0)
                    .maxPrice(maxPrice != null ? maxPrice : 0.0)
                    .minRating(minRating != null ? minRating : 0.0)
                    .resultsCount(products.size())
                    .returnedProductIds(products.stream()
                            .map(p -> p.getId().toString())
                            .toList())
                    .processingTimeMs(System.currentTimeMillis() - startTime)
                    .timestamp(Instant.now().toString())
                    .build();

            eventProducer.publishAISearchEvent(event);
            log.info("Published AISearchEvent: query='{}', category='{}', minPrice={}, maxPrice={}",
                    originalQuery, effectiveCategory, minPrice, maxPrice);

        } catch (Exception e) {
            log.warn("Failed to publish AISearchEvent: {}", e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Infer the most common category from a list of products.
     * More robust than taking the first category - handles cases where
     * vector search returns a few irrelevant products mixed in.
     *
     * @param products List of products to analyze
     * @param limit    Maximum number of products to consider
     * @return Most common category name, or empty string if none found
     */
    private String inferMostCommonCategory(List<ProductDTO> products, int limit) {
        if (products == null || products.isEmpty()) {
            return "";
        }

        // Count category occurrences from top N products
        Map<String, Long> categoryCounts = products.stream()
                .limit(limit)
                .map(ProductDTO::getCategoryName)
                .filter(c -> c != null && !c.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        c -> c,
                        java.util.stream.Collectors.counting()
                ));

        if (categoryCounts.isEmpty()) {
            return "";
        }

        // Find the most common category
        return categoryCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private List<Map<String, Object>> productsToMap(List<ProductDTO> products) {
        return products.stream()
                .map(this::productToMap)
                .toList();
    }

    private Map<String, Object> productToMap(ProductDTO product) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", product.getId().toString());
        map.put("name", product.getName());
        map.put("price", product.getPrice());
        map.put("category", product.getCategoryName());
        map.put("brand", product.getBrand());
        map.put("rating", product.getRating());
        map.put("description", product.getDescription());
        map.put("imageUrl", product.getThumbnailUrl());
        map.put("inStock", product.getInStock());
        return map;
    }

    // ==================== Inner Classes ====================

    private record FunctionCallResult(List<ProductDTO> products, Map<String, Object> responseData) {}

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ChatResult {
        private String message;
        private List<ProductDTO> products;
        private Long processingTimeMs;
    }
}
