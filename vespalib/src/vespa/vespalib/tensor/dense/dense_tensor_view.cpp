// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include "dense_tensor_view.h"
#include "dense_tensor_apply.hpp"
#include "dense_tensor_reduce.hpp"
#include <vespa/vespalib/util/stringfmt.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/tensor/tensor_address_builder.h>
#include <vespa/vespalib/tensor/tensor_visitor.h>
#include <vespa/vespalib/eval/operation.h>
#include <sstream>

using vespalib::eval::TensorSpec;

namespace vespalib {
namespace tensor {

namespace {

string
dimensionsAsString(const eval::ValueType &type)
{
    std::ostringstream oss;
    bool first = true;
    oss << "[";
    for (const auto &dim : type.dimensions()) {
        if (!first) {
            oss << ",";
        }
        first = false;
        oss << dim.name << ":" << dim.size;
    }
    oss << "]";
    return oss.str();
}

size_t
calcCellsSize(const eval::ValueType &type)
{
    size_t cellsSize = 1;
    for (const auto &dim : type.dimensions()) {
        cellsSize *= dim.size;
    }
    return cellsSize;
}


void
checkCellsSize(const DenseTensorView &arg)
{
    auto cellsSize = calcCellsSize(arg.type());
    if (arg.cells().size() != cellsSize) {
        throw IllegalStateException(make_string("wrong cell size, "
                                                "expected=%zu, "
                                                "actual=%zu",
                                                cellsSize,
                                                arg.cells().size()));
    }
}

void
checkDimensions(const DenseTensorView &lhs, const DenseTensorView &rhs,
                vespalib::stringref operation)
{
    if (lhs.type() != rhs.type()) {
        throw IllegalStateException(make_string("mismatching dimensions for "
                                                "dense tensor %s, "
                                                "lhs dimensions = '%s', "
                                                "rhs dimensions = '%s'",
                                                operation.c_str(),
                                                dimensionsAsString(lhs.type()).c_str(),
                                                dimensionsAsString(rhs.type()).c_str()));
    }
    checkCellsSize(lhs);
    checkCellsSize(rhs);
}


/*
 * Join the cells of two tensors.
 *
 * The given function is used to calculate the resulting cell value
 * for overlapping cells.
 */
template <typename Function>
Tensor::UP
joinDenseTensors(const DenseTensorView &lhs, const DenseTensorView &rhs,
                 Function &&func)
{
    DenseTensor::Cells cells;
    cells.reserve(lhs.cells().size());
    auto rhsCellItr = rhs.cells().cbegin();
    for (const auto &lhsCell : lhs.cells()) {
        cells.push_back(func(lhsCell, *rhsCellItr));
        ++rhsCellItr;
    }
    assert(rhsCellItr == rhs.cells().cend());
    return std::make_unique<DenseTensor>(lhs.type(),
                                         std::move(cells));
}


template <typename Function>
Tensor::UP
joinDenseTensors(const DenseTensorView &lhs, const Tensor &rhs,
                 vespalib::stringref operation,
                 Function &&func)
{
    const DenseTensorView *view = dynamic_cast<const DenseTensorView *>(&rhs);
    if (view) {
        checkDimensions(lhs, *view, operation);
        return joinDenseTensors(lhs, *view, func);
    }
    const DenseTensor *dense = dynamic_cast<const DenseTensor *>(&rhs);
    if (dense) {
        DenseTensorView rhsView(*dense);
        checkDimensions(lhs, rhsView, operation);
        return joinDenseTensors(lhs, rhsView, func);
    }
    return Tensor::UP();
}

bool sameCells(DenseTensorView::CellsRef lhs, DenseTensorView::CellsRef rhs)
{
    if (lhs.size() != rhs.size()) {
        return false;
    }
    for (size_t i = 0; i < lhs.size(); ++i) {
        if (lhs[i] != rhs[i]) {
            return false;
        }
    }
    return true;
}

}


DenseTensorView::DenseTensorView(const DenseTensor &rhs)
    : _type(rhs.type()),
      _cells(rhs.cells())
{
}


bool
DenseTensorView::operator==(const DenseTensorView &rhs) const
{
    return (_type == rhs._type) && sameCells(_cells, rhs._cells);
}

eval::ValueType
DenseTensorView::getType() const
{
    return _type;
}

double
DenseTensorView::sum() const
{
    double result = 0.0;
    for (const auto &cell : _cells) {
        result += cell;
    }
    return result;
}

Tensor::UP
DenseTensorView::add(const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [](double lhsValue, double rhsValue)
                        { return lhsValue + rhsValue; });
}

Tensor::UP
DenseTensorView::subtract(const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [](double lhsValue, double rhsValue)
                        { return lhsValue - rhsValue; });
}

Tensor::UP
DenseTensorView::multiply(const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [](double lhsValue, double rhsValue)
                        { return lhsValue * rhsValue; });
}

Tensor::UP
DenseTensorView::min(const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [](double lhsValue, double rhsValue)
                        { return std::min(lhsValue, rhsValue); });
}

Tensor::UP
DenseTensorView::max(const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [](double lhsValue, double rhsValue)
                        { return std::max(lhsValue, rhsValue); });
}

Tensor::UP
DenseTensorView::match(const Tensor &arg) const
{
    return joinDenseTensors(*this, arg, "match",
                            [](double lhsValue, double rhsValue)
                            { return (lhsValue * rhsValue); });
}

Tensor::UP
DenseTensorView::apply(const CellFunction &func) const
{
    Cells newCells(_cells.size());
    auto itr = newCells.begin();
    for (const auto &cell : _cells) {
        *itr = func.apply(cell);
        ++itr;
    }
    assert(itr == newCells.end());
    return std::make_unique<DenseTensor>(_type, std::move(newCells));
}

Tensor::UP
DenseTensorView::sum(const vespalib::string &dimension) const
{
    return dense::reduce(*this, { dimension },
                          [](double lhsValue, double rhsValue)
                          { return lhsValue + rhsValue; });
}

bool
DenseTensorView::equals(const Tensor &arg) const
{
    const DenseTensorView *view = dynamic_cast<const DenseTensorView *>(&arg);
    if (view) {
        return *this == *view;
    }
    const DenseTensor *dense = dynamic_cast<const DenseTensor *>(&arg);
    if (dense) {
        return *this == DenseTensorView(*dense);
    }
    return false;
}

vespalib::string
DenseTensorView::toString() const
{
    std::ostringstream stream;
    stream << *this;
    return stream.str();
}

Tensor::UP
DenseTensorView::clone() const
{
    return std::make_unique<DenseTensor>(_type,
                                         Cells(_cells.cbegin(), _cells.cend()));
}

namespace {

void
buildAddress(const DenseTensorCellsIterator &itr, TensorSpec::Address &address)
{
    auto addressItr = itr.address().begin();
    for (const auto &dim : itr.type().dimensions()) {
        address.emplace(std::make_pair(dim.name, TensorSpec::Label(*addressItr++)));
    }
    assert(addressItr == itr.address().end());
}

}

TensorSpec
DenseTensorView::toSpec() const
{
    TensorSpec result(getType().to_spec());
    TensorSpec::Address address;
    for (CellsIterator itr(_type, _cells); itr.valid(); itr.next()) {
        buildAddress(itr, address);
        result.add(address, itr.cell());
        address.clear();
    }
    return result;
}

void
DenseTensorView::print(std::ostream &out) const
{
    // TODO (geirst): print on common format.
    out << "[ ";
    bool first = true;
    for (const auto &dim : _type.dimensions()) {
        if (!first) {
            out << ", ";
        }
        out << dim.name << ":" << dim.size;
        first = false;
    }
    out << " ] { ";
    first = true;
    for (const auto &cell : cells()) {
        if (!first) {
            out << ", ";
        }
        out << cell;
        first = false;
    }
    out << " }";
}

void
DenseTensorView::accept(TensorVisitor &visitor) const
{
    CellsIterator iterator(_type, _cells);
    TensorAddressBuilder addressBuilder;
    TensorAddress address;
    vespalib::string label;
    while (iterator.valid()) {
        addressBuilder.clear();
        auto rawIndex = iterator.address().begin();
        for (const auto &dimension : _type.dimensions()) {
            label = vespalib::make_string("%zu", *rawIndex);
            addressBuilder.add(dimension.name, label);
            ++rawIndex;
        }
        address = addressBuilder.build();
        visitor.visit(address, iterator.cell());
        iterator.next();
    }
}

Tensor::UP
DenseTensorView::apply(const eval::BinaryOperation &op, const Tensor &arg) const
{
    return dense::apply(*this, arg,
                        [&op](double lhsValue, double rhsValue)
                        { return op.eval(lhsValue, rhsValue); });
}

Tensor::UP
DenseTensorView::reduce(const eval::BinaryOperation &op,
                        const std::vector<vespalib::string> &dimensions) const
{
    return dense::reduce(*this,
                         (dimensions.empty() ? _type.dimension_names() : dimensions),
                         [&op](double lhsValue, double rhsValue)
                         { return op.eval(lhsValue, rhsValue); });
}

} // namespace vespalib::tensor
} // namespace vespalib
