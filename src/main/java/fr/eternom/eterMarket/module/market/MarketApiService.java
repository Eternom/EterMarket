package fr.eternom.eterMarket.module.market;

import fr.eternom.eterMarket.api.MarketApi;
import fr.eternom.eterMarket.module.job.JobRepository;
import fr.eternom.eterMarket.module.job.JobService;
import fr.eternom.eterMarket.module.stock.StockRepository;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** L'API d'EterMarket (MarketApi) : le plugin lui-même, vu de l'extérieur. */
public class MarketApiService implements MarketApi {

    private final JobService jobs;
    private final JobRepository jobRepository;
    private final StockRepository stock;

    public MarketApiService(JobService jobs, JobRepository jobRepository, StockRepository stock) {
        this.jobs = jobs;
        this.jobRepository = jobRepository;
        this.stock = stock;
    }

    @Override
    public List<String> jobs() {
        return List.copyOf(jobs.jobs().icons().keySet());
    }

    @Override
    public String jobName(CommandSender viewer, String job) {
        return jobs.jobName(viewer, job);
    }

    @Override
    public Optional<String> job(UUID player) {
        return jobRepository.member(player).map(JobRepository.Member::job).filter(job -> job != null && !job.isBlank());
    }

    @Override
    public long stock(Material material) {
        return stock.amount(material);
    }

    @Override
    public void addStock(Material material, long amount) {
        if (amount > 0) {
            stock.add(material, amount);
        }
    }

    @Override
    public boolean takeStock(Material material, long amount) {
        return amount <= 0 || stock.take(material, amount);
    }
}
