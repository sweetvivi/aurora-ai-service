package com.hub.backend.product;

import com.hub.backend.common.ProductNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public List<ProductResponse> findAll() {
        return repository.findAll(Sort.by("id").ascending()).stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse findById(Long id) {
        return ProductResponse.from(getProduct(id));
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        return ProductResponse.from(repository.save(new Product(request.name(), request.price())));
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = getProduct(id);
        product.update(request.name(), request.price());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(getProduct(id));
    }

    private Product getProduct(Long id) {
        return repository.findById(id).orElseThrow(() -> new ProductNotFoundException(id));
    }
}
