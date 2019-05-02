# Minimalistic blog engine

https://grumpy.website

## Runtime dependencies

- imagemagick
- mailutils

## Development

```
mkdir grumpy_data
echo "nikitonsky" >> grumpy_data/FORCED_USER
echo "http://localhost:8080" >> grumpy_data/HOSTNAME
./scripts/repl.sh
open http://localhost:8080
```