
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/*

We are building a simple Spring Boot–based microservice.

Implement Product class and ProductPurchaseCounterService.

The service is called whenever a product is purchased.
Each purchase should increment a counter by one for a given product.
All counters should be stored in-memory.

*/
@RestController
@RequestMapping("/products")
class ProductController(counterService: ProductPurchaseCounterService) {
    private val counterService: ProductPurchaseCounterService

    init {
        this.counterService = counterService
    }

    @PostMapping("/purchase")
    fun purchaseProduct(
        @RequestParam id: String?,
        @RequestParam categories: MutableList<String?>?,
        @RequestParam price: BigDecimal?,
        @RequestParam currency: String?
    ): ResponseEntity<Void?> {
        val product: Product = Product(id, categories!!.toList(), price, currency)
        counterService.registerPurchase(product)
        return ResponseEntity.ok().build<Void>()
    }
}

data class Product(
    private val id: String?,
    private val categories: List<String?>,
    private val price: BigDecimal?,
    private val currency: String?
) 

class ProductPurchaseCounterService() {
    private val counters: ConcurrentHashMap<Product, AtomicInteger> = ConcurrentHashMap()


    fun registerPurchase(product: Product): Unit {
        counters.computeIfAbsent(product) { AtomicInteger(0) }.incrementAndGet()
    }
}
